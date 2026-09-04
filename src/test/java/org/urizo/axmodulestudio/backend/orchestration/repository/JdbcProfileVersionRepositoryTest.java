package org.urizo.axmodulestudio.backend.orchestration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileVersionException;

class JdbcProfileVersionRepositoryTest {

    private static final UUID CREDENTIAL_ID =
            UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID PROFILE_VERSION_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final String AUTHORIZATION = "Bearer local-service-test-token";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC);
    private final JdbcProfileVersionRepository repository =
            new JdbcProfileVersionRepository(jdbc, transactions, objectMapper, clock);

    @Test
    void remainsProxyableForRepositoryAdvice() {
        assertThat(Modifier.isFinal(JdbcProfileVersionRepository.class.getModifiers())).isFalse();
    }

    @BeforeEach
    void executeTransactionCallbacks() {
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticatesAndReturnsTheStoredSnapshot() throws Exception {
        validCredential();
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("status")).thenReturn("ACTIVE");
        when(resultSet.getString("snapshot_json")).thenReturn("""
                {"contractVersion":"1.0","profileVersionId":"77777777-7777-4777-8777-777777777777"}
                """);
        when(jdbc.query(
                argThat(sql -> sql.contains("FROM app.ai_profile_version")),
                any(RowMapper.class),
                eq(PROFILE_VERSION_ID))).thenAnswer(invocation -> {
                    RowMapper<ProfileVersionRepository.StoredProfileVersion> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(resultSet, 0));
                });

        Optional<ProfileVersionRepository.StoredProfileVersion> result =
                repository.findById(AUTHORIZATION, PROFILE_VERSION_ID);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(result.orElseThrow().snapshot().path("contractVersion").asText()).isEqualTo("1.0");
        verify(jdbc).update(
                argThat(sql -> sql.contains("SET last_used_at")),
                any(),
                eq(CREDENTIAL_ID));
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsEmptyAfterSuccessfulAuthenticationWhenTheVersionIsMissing() {
        validCredential();
        when(jdbc.query(
                argThat(sql -> sql.contains("FROM app.ai_profile_version")),
                any(RowMapper.class),
                eq(PROFILE_VERSION_ID))).thenReturn(List.of());

        assertThat(repository.findById(AUTHORIZATION, PROFILE_VERSION_ID)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsUnknownCredentialsBeforeReadingTheProfile() {
        when(jdbc.query(
                argThat(sql -> sql.contains("FROM app.coding_service_credential")),
                any(RowMapper.class),
                any(),
                any(),
                any())).thenReturn(List.of());

        assertThatThrownBy(() -> repository.findById(AUTHORIZATION, PROFILE_VERSION_ID))
                .isInstanceOfSatisfying(ProfileVersionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("SERVICE_AUTHENTICATION_FAILED");
                    assertThat(failure.status().value()).isEqualTo(401);
                    assertThat(failure.retryable()).isFalse();
                });
        verify(jdbc, never()).query(
                argThat(sql -> sql.contains("FROM app.ai_profile_version")),
                any(RowMapper.class),
                any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void translatesDatabaseFailuresToRetryableServiceUnavailable() {
        when(jdbc.query(
                argThat(sql -> sql.contains("FROM app.coding_service_credential")),
                any(RowMapper.class),
                any(),
                any(),
                any())).thenThrow(new DataAccessResourceFailureException("offline"));

        assertThatThrownBy(() -> repository.findById(AUTHORIZATION, PROFILE_VERSION_ID))
                .isInstanceOfSatisfying(ProfileVersionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("INTERNAL_TRANSIENT_ERROR");
                    assertThat(failure.status().value()).isEqualTo(503);
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.retryAfterMs()).isEqualTo(1_000L);
                });
    }

    @Test
    void rejectsMalformedAuthorizationWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> repository.findById("Basic secret", PROFILE_VERSION_ID))
                .isInstanceOfSatisfying(ProfileVersionException.class,
                        failure -> assertThat(failure.code()).isEqualTo("SERVICE_AUTHENTICATION_FAILED"));
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsANewVersionWithServerOwnedIdentityUsingOnlyInsert() throws Exception {
        ObjectNode authoring = objectMapper.createObjectNode();
        authoring.putArray("nodes");
        authoring.putArray("edges");
        authoring.putObject("config");
        authoring.putObject("modelBindings");
        authoring.putObject("toolBindings");
        authoring.putObject("toolPolicy");
        authoring.put("guardrailProfileKey", "central.default");
        when(jdbc.queryForObject(
                argThat(sql -> sql.contains("MAX(profile_version)")),
                eq(Integer.class),
                eq("LLM_OPS"))).thenReturn(3);
        when(jdbc.update(
                argThat(sql -> sql.contains("INSERT INTO app.ai_profile_version")),
                eq(PROFILE_VERSION_ID),
                eq("LLM_OPS"),
                eq(3),
                argThat(value -> value instanceof String json
                        && json.contains(PROFILE_VERSION_ID.toString())
                        && json.contains("\"profileVersion\":3"))))
                .thenReturn(1);
        when(jdbc.query(
                argThat(sql -> sql.contains("WHERE profile_version_id = ?")),
                any(RowMapper.class),
                eq(PROFILE_VERSION_ID))).thenReturn(List.of(adminVersion("DRAFT", 3)));

        ProfileVersionRepository.AdminStoredProfileVersion created =
                repository.createDraft("LLM_OPS", PROFILE_VERSION_ID, authoring);

        assertThat(created.profileVersion()).isEqualTo(3);
        assertThat(created.status()).isEqualTo("DRAFT");
        verify(jdbc).execute("LOCK TABLE app.ai_profile_version IN SHARE ROW EXCLUSIVE MODE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void activatesADraftAndDeactivatesOnlyThePreviousActiveVersion() {
        ProfileVersionRepository.AdminStoredProfileVersion draft = adminVersion("DRAFT", 3);
        ProfileVersionRepository.AdminStoredProfileVersion active = adminVersion("ACTIVE", 3);
        when(jdbc.query(
                argThat(sql -> sql.contains("WHERE profile_version_id = ?")),
                any(RowMapper.class),
                eq(PROFILE_VERSION_ID))).thenReturn(List.of(draft), List.of(active));
        when(jdbc.update(
                argThat(sql -> sql != null && sql.contains("status = 'INACTIVE'")),
                eq("LLM_OPS"))).thenReturn(1);
        when(jdbc.update(
                argThat(sql -> sql != null && sql.contains("status = 'ACTIVE'")),
                eq(PROFILE_VERSION_ID))).thenReturn(1);

        ProfileVersionRepository.AdminStoredProfileVersion activated =
                repository.activate(PROFILE_VERSION_ID).orElseThrow();

        assertThat(activated.status()).isEqualTo("ACTIVE");
        verify(jdbc).update(
                argThat(sql -> sql != null && sql.contains("status = 'INACTIVE'")),
                eq("LLM_OPS"));
        verify(jdbc).update(
                argThat(sql -> sql != null && sql.contains("status = 'ACTIVE'")),
                eq(PROFILE_VERSION_ID));
    }

    @SuppressWarnings("unchecked")
    private void validCredential() {
        when(jdbc.query(
                argThat(sql -> sql.contains("FROM app.coding_service_credential")),
                any(RowMapper.class),
                any(),
                any(),
                any())).thenReturn(List.of(CREDENTIAL_ID));
        when(jdbc.update(anyString(), any(), eq(CREDENTIAL_ID))).thenReturn(1);
    }

    private ProfileVersionRepository.AdminStoredProfileVersion adminVersion(
            String status, int version) {
        JsonNode snapshot = objectMapper.createObjectNode()
                .put("contractVersion", "1.0")
                .put("profileVersionId", PROFILE_VERSION_ID.toString())
                .put("profileKey", "LLM_OPS")
                .put("profileVersion", version);
        return new ProfileVersionRepository.AdminStoredProfileVersion(
                PROFILE_VERSION_ID,
                "LLM_OPS",
                version,
                status,
                OffsetDateTime.parse("2026-08-31T00:00:00Z").toInstant(),
                snapshot);
    }
}
