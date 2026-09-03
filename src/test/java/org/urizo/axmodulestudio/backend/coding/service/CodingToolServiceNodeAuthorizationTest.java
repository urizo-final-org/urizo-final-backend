package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileToolBindingPolicy;

class CodingToolServiceNodeAuthorizationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void authorizesAgainstTheActualStageNodeInsteadOfTheFixedJobGraphStep()
            throws Exception {
        ObjectNode snapshot = snapshot();
        ProfileToolBindingPolicy full = decode(snapshot);
        String fixedJobGraphStep = "coding";

        assertThat(fixedJobGraphStep).isNotEqualTo("code");
        assertThatCode(() -> CodingToolService.requireNodeToolAllowed(
                full, "code", "apply_patch")).doesNotThrowAnyException();
        assertDenied(full, "review", "apply_patch");

        snapshot.withObject("toolBindings").withObject("code").remove("apply_patch");
        assertDenied(decode(snapshot), "code", "apply_patch");
    }

    @Test
    void failsClosedWithoutANewSnapshotNodeAndKeepsLegacyGlobalFallback() throws Exception {
        ProfileToolBindingPolicy full = decode(snapshot());

        assertDenied(full, null, "read_file");
        assertThatCode(() -> CodingToolService.requireNodeToolAllowed(
                ProfileToolBindingPolicy.legacy(Set.of("read_file")),
                null, "read_file")).doesNotThrowAnyException();
    }

    private static void assertDenied(
            ProfileToolBindingPolicy policy, String nodeId, String toolName) {
        assertThatThrownBy(() -> CodingToolService.requireNodeToolAllowed(
                policy, nodeId, toolName))
                .isInstanceOfSatisfying(CodingToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TOOL_NOT_ALLOWED"));
    }

    private static ProfileToolBindingPolicy decode(ObjectNode snapshot) {
        return ProfileToolBindingPolicy.decode(
                snapshot, CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet());
    }

    private static ObjectNode snapshot() throws Exception {
        return (ObjectNode) OBJECT_MAPPER.readTree(Files.readString(Path.of(
                "contracts/fixtures/orchestration/llm-ops-coding-handler.snapshot.valid.json")));
    }
}
