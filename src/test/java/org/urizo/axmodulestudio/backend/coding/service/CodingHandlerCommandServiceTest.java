package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;

class CodingHandlerCommandServiceTest {

    @Test
    void enforcesApprovalStageRoleMatrixWithoutBodyRoleInput() {
        for (CodingHandlerContract.ApprovalStage stage :
                CodingHandlerContract.ApprovalStage.values()) {
            assertThatCode(() -> CodingHandlerCommandService.requireRole(
                    AdminRole.SUPER_ADMIN, stage)).doesNotThrowAnyException();
        }
        for (CodingHandlerContract.ApprovalStage stage : List.of(
                CodingHandlerContract.ApprovalStage.SCOPE,
                CodingHandlerContract.ApprovalStage.CANDIDATE,
                CodingHandlerContract.ApprovalStage.CMS)) {
            assertThatCode(() -> CodingHandlerCommandService.requireRole(
                    AdminRole.GENERAL_ADMIN, stage)).doesNotThrowAnyException();
        }
        for (CodingHandlerContract.ApprovalStage stage : List.of(
                CodingHandlerContract.ApprovalStage.GITHUB,
                CodingHandlerContract.ApprovalStage.DEPLOY)) {
            assertThatThrownBy(() -> CodingHandlerCommandService.requireRole(
                    AdminRole.GENERAL_ADMIN, stage))
                    .isInstanceOf(CodingJobLifecycleException.class);
        }
        for (CodingHandlerContract.ApprovalStage stage :
                CodingHandlerContract.ApprovalStage.values()) {
            assertThatThrownBy(() -> CodingHandlerCommandService.requireRole(
                    AdminRole.GENERAL_USER, stage))
                    .isInstanceOf(CodingJobLifecycleException.class);
        }
    }

    @Test
    void authoritativeCreateUsesOnlyTheAuthenticatedActor() {
        UUID actorId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        AuthenticatedActor actor =
                new AuthenticatedActor(actorId, "admin", AdminRole.GENERAL_ADMIN);
        CodingHandlerContract.CreateCodingJobRequest request =
                new CodingHandlerContract.CreateCodingJobRequest(
                        "1.0",
                        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                        UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                        UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
                        "start",
                        "sha1:1111111111111111111111111111111111111111",
                        "sha256:2222222222222222222222222222222222222222222222222222222222222222",
                        "sha256:3333333333333333333333333333333333333333333333333333333333333333",
                        "coding-v2",
                        List.of("TOOL_CALLING"),
                        List.of("start"),
                        Instant.parse("2026-08-31T00:00:00Z"),
                        "private natural-language request");

        CodingJobLifecycleContract.CreateRequest authoritative =
                CodingHandlerCommandService.authoritativeCreateRequest(actor, request);

        assertThat(authoritative.actorId()).isEqualTo(actorId);
        assertThat(authoritative.projectId()).isEqualTo(request.projectId());
        assertThat(authoritative.repositoryId()).isEqualTo(request.repositoryId());
    }
}
