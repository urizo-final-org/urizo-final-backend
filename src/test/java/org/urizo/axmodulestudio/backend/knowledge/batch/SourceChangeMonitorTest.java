package org.urizo.axmodulestudio.backend.knowledge.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.integration.ConnectorDocumentClient;
import org.urizo.axmodulestudio.backend.knowledge.integration.ConnectorSecretResolver;

/**
 * 변경 분류(AXMS-AI02-022). 신규·수정·소멸의 정의가 틀리면 알림이 거짓말을 한다 —
 * 특히 해시는 수집이 저장하는 형식과 같은 자로 재야 "전부 수정됨" 오탐이 없다.
 */
class SourceChangeMonitorTest {

    private static ProductApiContract.PreviewDocument document(String id, String content) {
        return new ProductApiContract.PreviewDocument(
                id, "제목 " + id, content, List.of("분류"),
                URI.create("https://www.bizinfo.go.kr/" + id),
                Instant.parse("2026-09-12T00:00:00Z"), null);
    }

    @Test
    void classifiesAddedModifiedAndMissingByIdAndDigest() {
        Map<String, String> active = Map.of(
                "PBLN_A", ProductBatchService.sha256("그대로인 본문"),
                "PBLN_B", ProductBatchService.sha256("옛 본문"),
                "PBLN_GONE", ProductBatchService.sha256("창 밖으로 밀려날 본문"));

        SourceChangeMonitor.Diff diff = SourceChangeMonitor.diff(active, List.of(
                document("PBLN_A", "그대로인 본문"),
                document("PBLN_B", "고쳐 쓴 본문"),
                document("PBLN_NEW", "새 공고 본문")));

        assertThat(diff.added()).isEqualTo(1);
        assertThat(diff.modified()).isEqualTo(1);
        assertThat(diff.missing()).isEqualTo(1);
    }

    @Test
    void identicalWindowsReportNothing() {
        Map<String, String> active = Map.of("PBLN_A", ProductBatchService.sha256("본문"));

        SourceChangeMonitor.Diff diff = SourceChangeMonitor.diff(
                active, List.of(document("PBLN_A", "본문")));

        assertThat(diff).isEqualTo(new SourceChangeMonitor.Diff(0, 0, 0));
    }

    /** 꺼진 모니터는 DB도 원천도 건드리지 않는다 — 촬영 중 갑자기 도는 일이 없어야 한다. */
    @Test
    void aDisabledMonitorTouchesNothing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ConnectorDocumentClient connectors = mock(ConnectorDocumentClient.class);
        ConnectorSecretResolver secrets = mock(ConnectorSecretResolver.class);
        SourceChangeMonitor monitor = new SourceChangeMonitor(
                jdbc, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                connectors, secrets, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC),
                false, 500);

        monitor.check();

        verifyNoInteractions(jdbc, connectors, secrets);
    }
}
