package org.urizo.axmodulestudio.backend.knowledge.batch;

import java.lang.reflect.Method;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderResponseFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 두 출력 스키마가 게이트웨이의 strict 검증을 통과하는지 고정한다(AXMS-AI02-027).
 *
 * <p>이 테스트가 없었을 때 스키마 오류를 <b>컨테이너를 재빌드해 호출해 보고서야</b>
 * 알았다. strict 출력 규칙은 좁다 — 모든 object가 properties·required·
 * additionalProperties(false)를 갖고 <b>required가 전체 속성과 정확히 같아야</b> 하며,
 * string/integer는 필드가 하나뿐이라 {@code enum}을 쓸 수 없다. 스키마를 손볼 때 이
 * 테스트가 먼저 실패해야 배포까지 가지 않는다.
 */
class TourDiagnosisSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode schema(String methodName) throws Exception {
        TourDiagnosisAgent agent =
                new TourDiagnosisAgent(null, null, null, objectMapper, null);
        Method method = TourDiagnosisAgent.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (JsonNode) method.invoke(agent);
    }

    @Test
    void actionSchemaPassesStrictOutputValidation() throws Exception {
        JsonNode actionSchema = schema("actionSchema");

        assertThatCode(() -> ProviderResponseFormat.jsonSchema(actionSchema))
                .doesNotThrowAnyException();
    }

    @Test
    void verdictSchemaPassesStrictOutputValidation() throws Exception {
        JsonNode verdictSchema = schema("verdictSchema");

        assertThatCode(() -> ProviderResponseFormat.jsonSchema(verdictSchema))
                .doesNotThrowAnyException();
    }

    /** strict 출력의 핵심 규칙 — required가 전체 속성과 같아야 한다. */
    @Test
    void everyPropertyIsRequiredInBothSchemas() throws Exception {
        for (String name : new String[] {"actionSchema", "verdictSchema"}) {
            JsonNode schema = schema(name);
            assertThat(schema.path("required").size())
                    .as("%s: required must cover every property", name)
                    .isEqualTo(schema.path("properties").size());
            assertThat(schema.path("additionalProperties").booleanValue())
                    .as("%s: additionalProperties must be false", name)
                    .isFalse();
        }
    }
}
