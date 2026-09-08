package org.urizo.axmodulestudio.backend.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * 자료 갱신 요청 테이블이 "표시"에서 멈추는지 지킨다.
 *
 * <p>이 테이블은 사람 사이의 전달을 기록할 뿐이며, 처리 주체·기한·에스컬레이션 같은
 * 워크플로 상태를 갖지 않는다. 그런 열이 생기면 요청 목록이 두 번째 승인 체계가 된다.
 */
class ActivationRequestMigrationTest {

    private static final String MIGRATION =
            "src/main/resources/db/migration/"
                    + "V20260907033400506__create_knowledge_activation_request.sql";

    @Test
    void theTableRecordsWhoAskedForWhatAndNothingElse() throws IOException {
        String migration = Files.readString(Path.of(MIGRATION));

        assertThat(migration)
                .contains("CREATE TABLE app.knowledge_activation_request")
                .contains("knowledge_base_id UUID NOT NULL REFERENCES app.knowledge_base")
                .contains("requested_by UUID NOT NULL")
                .contains("GRANT SELECT, INSERT, UPDATE ON app.knowledge_activation_request TO cms_app")
                // 워크플로가 아니다 — 담당자 배정·기한·단계가 생기면 승인 체계가 둘이 된다.
                .doesNotContain("assignee")
                .doesNotContain("due_at")
                .doesNotContain("priority")
                .doesNotContain("escalat");
    }

    @Test
    void aVersionlessRequestIsAllowedBecauseTheVersionMayNotExistYet() throws IOException {
        String migration = Files.readString(Path.of(MIGRATION));
        // "이 버전을 켜 주세요"와 "새로 만들어 주세요"를 한 테이블로 받는다.
        assertThat(migration).contains("knowledge_version_id UUID REFERENCES app.knowledge_version");
        assertThat(migration).doesNotContain("knowledge_version_id UUID NOT NULL");
    }

    @Test
    void repeatedAsksCannotStackIntoAnUnreadableList() throws IOException {
        String migration = Files.readString(Path.of(MIGRATION));
        assertThat(migration)
                .contains("CREATE UNIQUE INDEX uq_knowledge_activation_request_open")
                .contains("WHERE status = 'OPEN'");
    }

    @Test
    void aResolvedRowMustCarryItsResolutionTime() throws IOException {
        String migration = Files.readString(Path.of(MIGRATION));
        // 상태와 시각이 어긋나면 "언제 닫혔나"에 답할 수 없다.
        assertThat(migration).contains("CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))");
    }

    @Test
    void deleteIsNotGrantedSoAsksAreNotQuietlyRemoved() throws IOException {
        String migration = Files.readString(Path.of(MIGRATION));
        assertThat(migration).doesNotContain("DELETE ON app.knowledge_activation_request");
    }
}
