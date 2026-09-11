package org.urizo.axmodulestudio.backend.knowledge.batch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.integration.TourismSampleDocumentLoader;

/**
 * 임시. {@link TourismSampleDocumentLoader}와 함께 지운다.
 *
 * <p>{@code image_url}은 COLLECT 단계가 채우므로, 컬럼이 생기기 전에 빌드된 지식 버전의 행은
 * 값이 비어 있다. 그 버전들을 다시 빌드하지 않고도 사진이 나오게 채워 넣는다 — 시연에 쓰는
 * 버전을 새로 만들면 버전 번호와 지표 기록이 함께 흔들리기 때문이다.
 *
 * <p>본문·digest·임베딩·날짜는 건드리지 않는다. 사진은 표시용 메타데이터일 뿐이라 검색 결과와
 * 거절 판정이 이 실행 전후로 달라지지 않는다.
 *
 * <p><b>이미 값이 있는 행은 건너뛴다</b>({@code image_url IS NULL} 조건). 그래서 반복 실행이
 * 안전하고, 나중에 원천이 사진을 바꿔도 이 보조 처리가 덮어쓰지 않는다 — 덮어쓰기는 재빌드의 일이다.
 */
@Component
@Profile("local-full")
@Order(30)
class SourceDocumentPhotoBackfillRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SourceDocumentPhotoBackfillRunner.class);

    /** 한 번에 보내는 UPDATE 개수. 표본이 500건이라 한 묶음으로 끝난다. */
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    // 파라미터 이름으로 productTransactionTemplate을 고른다. TransactionTemplate 빈이 셋이고
    // @Primary가 없다 — KnowledgeStore가 쓰는 방식과 같다.
    SourceDocumentPhotoBackfillRunner(
            JdbcTemplate jdbc, TransactionTemplate productTransactionTemplate) {
        this.jdbc = jdbc;
        this.transactions = productTransactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Object[]> arguments = new ArrayList<>();
        for (ProductApiContract.PreviewDocument document : TourismSampleDocumentLoader.documents()) {
            if (document.imageUrl() != null) {
                arguments.add(new Object[] { document.imageUrl(), document.documentId() });
            }
        }
        if (arguments.isEmpty()) {
            return;
        }
        // 트랜잭션으로 감싼다. productDataSource가 autoCommit=false라 트랜잭션 밖의 UPDATE는
        // 예외 없이 커밋되지 않고 커넥션 반납 시 롤백된다 — 갱신 건수 로그를 남기고도 행이
        // 그대로다(KnowledgeStore의 같은 주의와 동일한 함정).
        Integer filled = transactions.execute(status -> {
            int count = 0;
            for (int start = 0; start < arguments.size(); start += BATCH_SIZE) {
                List<Object[]> slice = arguments.subList(
                        start, Math.min(start + BATCH_SIZE, arguments.size()));
                int[] updated = jdbc.batchUpdate(
                        "UPDATE app.source_document SET image_url = ? "
                                + "WHERE external_document_id = ? AND image_url IS NULL",
                        slice);
                count += Arrays.stream(updated).sum();
            }
            return count;
        });
        // 채운 것이 없으면 조용히 넘어간다 — 매 기동마다 로그를 남기지 않는다.
        if (filled != null && filled > 0) {
            LOG.info("Backfilled {} source document photos for versions built before the column existed.",
                    filled);
        }
    }
}
