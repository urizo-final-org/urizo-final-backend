package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;

class CodingApprovalIdTest {

    @Test
    void identityIncludesPipelineAttemptNodeStageAndRound() {
        UUID jobId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        UUID first = CodingApprovalId.forStage(
                jobId,
                1,
                "preview_approval",
                CodingHandlerContract.ApprovalStage.CANDIDATE,
                1);
        UUID replay = CodingApprovalId.forStage(
                jobId,
                1,
                "preview_approval",
                CodingHandlerContract.ApprovalStage.CANDIDATE,
                1);
        UUID retry = CodingApprovalId.forStage(
                jobId,
                2,
                "preview_approval",
                CodingHandlerContract.ApprovalStage.CANDIDATE,
                2);

        assertThat(first).isEqualTo(replay).isNotEqualTo(retry);
        assertThat(first.version()).isEqualTo(5);
        assertThat(first.variant()).isEqualTo(2);
    }
}
