package org.urizo.axmodulestudio.backend.connector;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.urizo.axmodulestudio.backend.product.ProductApiContract;

public final class DeterministicConnectorFixture {

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

    public static boolean hasGroundingOverlap(String query, String content) {
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+", -1)) {
            if (token.length() >= 2 && normalizedContent.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
