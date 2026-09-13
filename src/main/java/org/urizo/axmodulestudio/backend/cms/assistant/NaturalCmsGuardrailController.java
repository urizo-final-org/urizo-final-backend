package org.urizo.axmodulestudio.backend.cms.assistant;

import java.util.Map;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자연어 CMS 울타리 설정.
 *
 * <p>{@code /api/admin/cms/**} 아래라 SecurityConfig가 이미 SUPER_ADMIN으로 막는다.
 * 일반 관리자는 자연어로 변경을 요청하고, AI가 어디까지 쓸 수 있는지 정하는 것은 그와 같은
 * 결정이 아니다. 같은 이유로 Coding 울타리도 SUPER_ADMIN 전용이다.
 */
@RestController
@Validated
@Profile("dev & local-full")
@RequestMapping("/api/admin/cms/guardrail")
@ConditionalOnProperty(
        prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class NaturalCmsGuardrailController {

    private final NaturalCmsGuardrailAdminService guardrails;

    NaturalCmsGuardrailController(NaturalCmsGuardrailAdminService guardrails) {
        this.guardrails = guardrails;
    }

    @GetMapping
    public NaturalCmsGuardrailContract.GuardrailView view() {
        return guardrails.view();
    }

    @PutMapping
    public NaturalCmsGuardrailContract.GuardrailView save(
            @Valid @RequestBody NaturalCmsGuardrailContract.SaveRequest request) {
        return guardrails.save(request);
    }

    @ExceptionHandler(NaturalCmsException.class)
    ResponseEntity<Map<String, Object>> failure(NaturalCmsException failure) {
        return ResponseEntity.status(failure.status()).body(Map.of(
                "code", failure.code(),
                "message", failure.getMessage(),
                "retryable", failure.retryable()));
    }
}
