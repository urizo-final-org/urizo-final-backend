package org.urizo.axmodulestudio.backend.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileVersionRepository;

class ProfileVersionServiceTest {

    private static final UUID PROFILE_VERSION_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final String AUTHORIZATION = "Bearer test-token";

    private final ProfileVersionRepository repository = mock(ProfileVersionRepository.class);
    private final ProfileVersionService service = new ProfileVersionService(repository);

    @Test
    void returnsOnlyActiveSnapshots() {
        JsonNode snapshot = JsonNodeFactory.instance.objectNode().put("contractVersion", "1.0");
        when(repository.findById(AUTHORIZATION, PROFILE_VERSION_ID)).thenReturn(Optional.of(
                new ProfileVersionRepository.StoredProfileVersion("ACTIVE", snapshot)));

        JsonNode returned = service.getActive(AUTHORIZATION, PROFILE_VERSION_ID);

        assertThat(returned).isEqualTo(snapshot);
        assertThat(returned).isNotSameAs(snapshot);
    }

    @Test
    void reportsMissingVersionsWithoutConflatingThemWithInactiveVersions() {
        when(repository.findById(AUTHORIZATION, PROFILE_VERSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getActive(AUTHORIZATION, PROFILE_VERSION_ID))
                .isInstanceOfSatisfying(ProfileVersionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PROFILE_VERSION_NOT_FOUND");
                    assertThat(failure.status().value()).isEqualTo(404);
                    assertThat(failure.retryable()).isFalse();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "INACTIVE"})
    void rejectsDraftAndInactiveVersions(String status) {
        JsonNode snapshot = JsonNodeFactory.instance.objectNode().put("contractVersion", "1.0");
        when(repository.findById(AUTHORIZATION, PROFILE_VERSION_ID)).thenReturn(Optional.of(
                new ProfileVersionRepository.StoredProfileVersion(status, snapshot)));

        assertThatThrownBy(() -> service.getActive(AUTHORIZATION, PROFILE_VERSION_ID))
                .isInstanceOfSatisfying(ProfileVersionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PROFILE_VERSION_NOT_ACTIVE");
                    assertThat(failure.status().value()).isEqualTo(409);
                    assertThat(failure.retryable()).isFalse();
                });
    }
}
