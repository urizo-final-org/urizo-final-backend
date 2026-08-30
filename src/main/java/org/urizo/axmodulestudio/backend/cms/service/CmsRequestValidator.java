package org.urizo.axmodulestudio.backend.cms.service;

import static org.urizo.axmodulestudio.backend.cms.service.CmsServiceException.invalidRequest;

import java.util.Comparator;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

/**
 * 저장하지 않고 요청 DTO의 제약만 검사한다.
 *
 * <p>Controller의 {@code @Valid}는 저장 직전에만 동작하므로 미리보기 단계에서는 쓸 수 없다.
 * 같은 제약을 미리 확인해야 "미리보기 정상 → 승인 → 저장 실패"를 막을 수 있다.
 */
@Component
@Profile("local-full")
public class CmsRequestValidator {

    private final Validator validator;

    public CmsRequestValidator(Validator validator) {
        this.validator = validator;
    }

    /**
     * 위반이 있으면 첫 항목을 {@link CmsServiceException.Kind#INVALID_REQUEST}로 알린다.
     * 속성 이름 순으로 정렬해 같은 입력이 항상 같은 사유를 낸다.
     */
    public <T> void validate(T request) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return;
        }
        ConstraintViolation<T> first = violations.stream()
                .min(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .orElseThrow();
        throw invalidRequest(first.getPropertyPath() + " " + first.getMessage());
    }
}
