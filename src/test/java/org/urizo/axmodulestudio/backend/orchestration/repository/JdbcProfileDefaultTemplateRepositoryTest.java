package org.urizo.axmodulestudio.backend.orchestration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcProfileDefaultTemplateRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcProfileDefaultTemplateRepository repository =
            new JdbcProfileDefaultTemplateRepository(jdbc, transactions, objectMapper);

    @BeforeEach
    void executeTransactionCallbacks() {
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void loadsAndUpsertsOneRowPerProfileKey() throws Exception {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.putArray("nodes");
        storedQuery(snapshot);
        when(jdbc.update(
                argThat(sql -> sql.contains("ON CONFLICT (profile_key) DO UPDATE")),
                eq("LLM_OPS"),
                anyString())).thenReturn(1);

        assertThat(repository.findByProfileKey("LLM_OPS"))
                .get()
                .extracting(ProfileDefaultTemplateRepository.StoredDefaultTemplate::profileKey)
                .isEqualTo("LLM_OPS");
        assertThat(repository.save("LLM_OPS", snapshot).snapshot()).isEqualTo(snapshot);
    }

    @SuppressWarnings("unchecked")
    private void storedQuery(JsonNode snapshot) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("profile_key")).thenReturn("LLM_OPS");
        when(resultSet.getObject("updated_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-09-04T00:00:00Z"));
        when(resultSet.getString("snapshot_json")).thenReturn(snapshot.toString());
        when(jdbc.query(
                argThat(sql -> sql.contains("FROM app.ai_profile_default_template")),
                any(RowMapper.class),
                eq("LLM_OPS"))).thenAnswer(invocation -> {
                    RowMapper<ProfileDefaultTemplateRepository.StoredDefaultTemplate> mapper =
                            invocation.getArgument(1);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
    }
}
