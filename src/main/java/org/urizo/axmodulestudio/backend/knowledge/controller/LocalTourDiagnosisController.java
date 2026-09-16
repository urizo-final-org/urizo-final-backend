package org.urizo.axmodulestudio.backend.knowledge.controller;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.urizo.axmodulestudio.backend.integration.ai.local.LocalDevRequestGuard;
import org.urizo.axmodulestudio.backend.knowledge.batch.TourDiagnosisAgent;

/**
 * 관광 품질 진단 에이전트의 로컬 실행 통로(AXMS-AI02-027).
 *
 * <p>보안은 {@link LocalDevRequestGuard}(루프백 + CSRF)다. 공통 SecurityConfig를 건드리지
 * 않는다 — provider-credentials·evaluation-sets가 이미 같은 방식으로 동작한다.
 *
 * <p>대상 버전이 관광 소속인지는 이 컨트롤러가 아니라 <b>도구가</b> 검사한다. 검사를 한
 * 곳에 두어야 나중에 다른 진입점이 생겨도 중기부가 새지 않는다.
 */
@RestController
@Profile("dev & local-full")
@RequestMapping("/internal/dev/tour-diagnosis")
public class LocalTourDiagnosisController {

    private final LocalDevRequestGuard requestGuard;
    private final TourDiagnosisAgent agent;
    private final Clock clock;

    LocalTourDiagnosisController(
            LocalDevRequestGuard requestGuard, TourDiagnosisAgent agent, Clock clock) {
        this.requestGuard = requestGuard;
        this.agent = agent;
        this.clock = clock;
    }

    /** 조사를 돌리고 단계와 진단을 함께 돌려준다. 화면이 단계를 순서대로 보여 준다. */
    @PostMapping("/{knowledgeVersionId}")
    ResponseEntity<Object> diagnose(
            @PathVariable UUID knowledgeVersionId, HttpServletRequest request) {
        requestGuard.requireMutation(request);
        if (!agent.enabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new SafeError(
                    "DIAGNOSIS_LLM_DISABLED",
                    "Set AXMS_DIAGNOSIS_LLM=true to run the quality diagnosis."));
        }
        TourDiagnosisAgent.Diagnosis diagnosis = agent.diagnose(knowledgeVersionId);
        return ResponseEntity.ok(new DiagnosisView(
                requestGuard.csrfToken(request), knowledgeVersionId,
                diagnosis.steps().stream().map(StepView::of).toList(),
                diagnosis.verdict(), diagnosis.stopReason(), diagnosis.promptVersion(),
                Instant.now(clock)));
    }

    record StepView(int order, String tool, String reason, boolean failed, JsonNode result) {
        static StepView of(TourDiagnosisAgent.Step step) {
            return new StepView(
                    step.order(), step.tool(), step.reason(), step.failed(), step.result());
        }
    }

    record DiagnosisView(
            String csrfToken, UUID knowledgeVersionId, List<StepView> steps,
            JsonNode verdict, String stopReason, String promptVersion, Instant checkedAt) {
    }

    record SafeError(String code, String message) {
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<SafeError> securityFailure(SecurityException failure) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new SafeError("LOCAL_CMS_ACCESS_DENIED", failure.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<SafeError> validationFailure(IllegalArgumentException failure) {
        return ResponseEntity.badRequest()
                .body(new SafeError("LOCAL_CMS_VALIDATION_FAILED", failure.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<SafeError> storageFailure(DataAccessException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new SafeError("LOCAL_SECRET_STORE_UNAVAILABLE",
                        "The local product store is unavailable."));
    }
}
