package org.urizo.axmodulestudio.backend.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

/**
 * 요청 생성이 실제로 커밋되는지 확인한다.
 *
 * <p><b>메우는 구멍</b>: {@code ActivationRequestServiceTest}는 {@code ProductStore}를 mock으로
 * 두어 DataSource를 타지 않는다. 그래서 9/7에 "201을 받고도 행이 남지 않는" 결함을 잡지 못했다.
 * {@code productDataSource}는 {@code autoCommit=false}(ProductRuntimeConfiguration:48)이고,
 * 이 코드베이스에서 트랜잭션을 여는 유일한 진입점이 {@code store.idempotent(...)}였는데
 * 이 경로는 멱등 Key를 요구하지 않기로 해서 그 진입점을 함께 잃었다.
 *
 * <p>mock 테스트가 검증하는 것(위임·중복 접기 규칙)과 이 테스트가 검증하는 것(커밋 여부)은
 * 층이 다르다. 둘 다 필요하다.
 *
 * <p>실 DB가 필요하므로 평소에는 건너뛴다. 로컬 스택이 떠 있을 때만 켠다:
 * {@code AXMS_RUN_KNOWLEDGE_DB_INTEGRATION=true}
 */
@SpringBootTest
@ActiveProfiles({"dev", "local-full"})
@EnabledIfEnvironmentVariable(named = "AXMS_RUN_KNOWLEDGE_DB_INTEGRATION", matches = "true")
class ActivationRequestCommitIntegrationTest {

    @Autowired
    private KnowledgeStore store;

    @Autowired
    private JdbcTemplate productJdbcTemplate;

    @Test
    void aCreatedRequestSurvivesTheConnectionThatWroteIt() {
        UUID knowledgeBaseId = anyKnowledgeBase();
        AuthenticatedActor actor = new AuthenticatedActor(
                UUID.randomUUID(), "통합 테스트 관리자", AdminRole.GENERAL_ADMIN);
        UUID traceId = UUID.randomUUID();

        ProductApiContract.ActivationRequestResponse created = store.createActivationRequest(
                knowledgeBaseId, traceId, actor,
                new ProductApiContract.CreateActivationRequestRequest("1.0", null, "커밋 확인"));
        try {
            // 응답이 아니라 DB에 묻는다. 커밋되지 않으면 호출을 감싼 커넥션이 반납되며
            // 롤백되므로, 새 커넥션으로 조회하는 이 단언만 그 차이를 본다.
            Integer rows = productJdbcTemplate.queryForObject(
                    "SELECT count(*) FROM app.knowledge_activation_request WHERE request_id = ?",
                    Integer.class, created.requestId());
            assertThat(rows).isEqualTo(1);

            // 같은 사람·같은 대상·열린 요청은 새 행을 만들지 않고 기존 행을 돌려준다.
            // 조회와 INSERT가 한 트랜잭션에 있어야 성립한다.
            ProductApiContract.ActivationRequestResponse again = store.createActivationRequest(
                    knowledgeBaseId, traceId, actor,
                    new ProductApiContract.CreateActivationRequestRequest("1.0", null, "두 번째 시도"));
            assertThat(again.requestId()).isEqualTo(created.requestId());
            assertThat(store.listOpenActivationRequests(knowledgeBaseId, traceId))
                    .extracting(ProductApiContract.ActivationRequestResponse::requestId)
                    .contains(created.requestId());
        }
        finally {
            productJdbcTemplate.update(
                    "DELETE FROM app.knowledge_activation_request WHERE request_id = ?",
                    created.requestId());
        }
    }

    /** 시연 데이터에 의존하지 않도록 아무 지식 베이스나 하나 고른다. */
    private UUID anyKnowledgeBase() {
        List<UUID> ids = productJdbcTemplate.query(
                "SELECT knowledge_base_id FROM app.knowledge_base LIMIT 1",
                (rs, row) -> rs.getObject(1, UUID.class));
        assertThat(ids).as("로컬 스택에 지식 베이스가 하나는 있어야 한다").isNotEmpty();
        return ids.get(0);
    }
}
