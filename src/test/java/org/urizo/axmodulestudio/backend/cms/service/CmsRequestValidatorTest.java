package org.urizo.axmodulestudio.backend.cms.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.BoardRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.MenuRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.TemplateRequest;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

class CmsRequestValidatorTest {

    private static ValidatorFactory factory;
    private static CmsRequestValidator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = new CmsRequestValidator(factory.getValidator());
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void acceptsRequestThatMeetsEveryConstraint() {
        assertThatCode(() -> validator.validate(new BoardRequest("공지사항", "안내")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankRequiredValueWithoutSaving() {
        assertThatThrownBy(() -> validator.validate(new BoardRequest(" ", "안내")))
                .isInstanceOf(CmsServiceException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsValueLongerThanAllowed() {
        assertThatThrownBy(() -> validator.validate(new BoardRequest("가".repeat(101), "안내")))
                .isInstanceOf(CmsServiceException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsTargetTypeOutsideAllowedPattern() {
        assertThatThrownBy(() -> validator.validate(
                new MenuRequest("소개", "/about", null, 0, "TEMPLATE", null)))
                .isInstanceOf(CmsServiceException.class)
                .hasMessageContaining("targetType");
    }

    @Test
    void rejectsColorOutsideHexPattern() {
        assertThatThrownBy(() -> validator.validate(new TemplateRequest(
                "BOLD", "파랑", "AX", "머리말", "꼬리말",
                "https://example.test/hero.png", "대표 문구", "설명", "버튼", "/about")))
                .isInstanceOf(CmsServiceException.class)
                .hasMessageContaining("primaryColor");
    }

    @Test
    void reportsTheSameReasonForTheSameInput() {
        MenuRequest request = new MenuRequest(" ", " ", null, -1, "NONE", null);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(CmsServiceException.class)
                .hasMessageContaining("displayOrder");
    }
}
