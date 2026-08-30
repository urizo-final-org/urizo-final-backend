package org.urizo.axmodulestudio.backend.knowledge.integration;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

public final class DeterministicConnectorFixture {

    private static final Logger LOG = LoggerFactory.getLogger(DeterministicConnectorFixture.class);

    private static final List<ProductApiContract.PreviewDocument> DOCUMENTS = List.of(
            new ProductApiContract.PreviewDocument(
                    "local-tourism-001",
                    "서울 야간 관광 안내",
                    "서울의 야간 관광 코스는 한강 산책로, 남산 전망대, 전통시장 문화 체험으로 구성됩니다. 운영 시간과 안전 공지를 방문 전에 확인하세요.",
                    List.of("tourism", "seoul"),
                    URI.create("https://fixture.invalid/documents/local-tourism-001"),
                    Instant.parse("2026-08-01T00:00:00Z")),
            new ProductApiContract.PreviewDocument(
                    "local-policy-002",
                    "지역 창업 지원 정책",
                    "지역 창업 지원 프로그램은 교육, 전문가 상담, 시제품 제작 지원을 제공합니다. 신청 자격과 접수 기간은 공고문 기준입니다.",
                    List.of("policy", "startup"),
                    URI.create("https://fixture.invalid/documents/local-policy-002"),
                    Instant.parse("2026-08-02T00:00:00Z")),
            new ProductApiContract.PreviewDocument(
                    "local-safety-003",
                    "공공시설 안전 이용 수칙",
                    "공공시설 이용자는 현장 안내 표지와 운영자 지시를 따라야 합니다. 긴급 상황에서는 119에 신고하고 지정 대피로를 이용하세요.",
                    List.of("safety", "public-facility"),
                    URI.create("https://fixture.invalid/documents/local-safety-003"),
                    Instant.parse("2026-08-03T00:00:00Z")));

    private DeterministicConnectorFixture() {
    }

    public static List<ProductApiContract.PreviewDocument> documents(int maxItems) {
        return List.copyOf(DOCUMENTS.subList(0, Math.min(maxItems, DOCUMENTS.size())));
    }

    public static int totalCount() {
        return DOCUMENTS.size();
    }

    public static boolean supports(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        }
        catch (IllegalArgumentException failure) {
            return false;
        }
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme())
                && host != null
                && (host.equals("fixture.invalid") || host.endsWith(".fixture.invalid"))
                && uri.getUserInfo() == null
                && uri.getQuery() == null
                && uri.getFragment() == null;
    }

    public static String vector(String text) {
        double[] values = new double[32];
        String[] tokens = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+", -1);
        for (String token : tokens) {
            if (!token.isBlank()) {
                int hash = token.hashCode();
                int index = Math.floorMod(hash, values.length);
                values[index] += ((hash & 1) == 0 ? 1.0 : -1.0);
            }
        }
        double norm = 0.0;
        for (double value : values) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            values[0] = 1.0;
            norm = 1.0;
        }
        List<String> encoded = new ArrayList<>(values.length);
        for (double value : values) {
            encoded.add(Double.toString(value / norm));
        }
        return "[" + String.join(",", encoded) + "]";
    }

    /**
     * 질의 토큰 하나라도 본문에 부분문자열로 포함되면 통과.
     *
     * <p>원형이 매칭되지 않으면 끝에서 한 글자씩 떼며 최소 2글자까지 재시도한다(접미절단).
     * 한국어 조사·어미가 붙은 토큰("반도식당의")이 본문 어간("반도식당")과 매칭되도록 하는
     * 완화이며, 토큰화 규칙(분리 정규식·소문자화·2자 이상)과 필터 위치(topK 뒤, 백필 없음,
     * 전원 탈락 시 REFUSED)는 바꾸지 않는다. 파이썬 참조 구현:
     * api-test rag/grounding_filter.py has_grounding_overlap_truncated().
     *
     * <p>절단 매칭은 어간이 일반명사로 붕괴할 수 있는 알려진 비용이 있어(예: "스시거제는"→"스시"),
     * 몇 글자를 깎아 매칭됐는지 INFO 로그로 남겨 추적 가능하게 한다.
     */
    public static boolean hasGroundingOverlap(String query, String content) {
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+", -1)) {
            if (token.length() < 2) {
                continue;
            }
            for (int end = token.length(); end >= 2; end--) {
                String stem = token.substring(0, end);
                if (normalizedContent.contains(stem)) {
                    if (end < token.length()) {
                        LOG.info("grounding suffix-truncation matched: token='{}' stem='{}' trimmed={}",
                                token, stem, token.length() - end);
                    }
                    return true;
                }
            }
        }
        return false;
    }
}
