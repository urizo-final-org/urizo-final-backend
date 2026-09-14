package org.urizo.axmodulestudio.backend.knowledge.batch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

/**
 * 여러 원천을 표준 문서 하나로 합친다(AXMS-AI02-023).
 *
 * <p>BASE가 문서 집합을 정하고 OVERLAY는 같은 문서 번호에 라벨 줄만 더한다. OVERLAY에도 문서를
 * 만들게 하면 "전체 관광 + 축제 날짜"가 아니라 두 코퍼스의 합집합이 되고, BASE에 없는 문서가
 * 조용히 늘어난다.
 *
 * <p>도메인 지식은 없다 — 수집기가 metadata 매핑을 {@code [라벨] 값} 줄로 본문에 싣는다는
 * 것만 안다. 관광의 행사 날짜든 다른 고객사의 다른 필드든 같은 규칙으로 합쳐진다.
 */
final class SourceMerge {

    private static final Pattern LABEL_LINE = Pattern.compile("^\\[([^\\]\\n]+)\\]");

    private SourceMerge() {
    }

    /** 합친 문서와, 그중 OVERLAY가 실제로 보강한 문서 수. 0이면 두 원천이 겹치지 않은 것이다. */
    record Result(List<ProductApiContract.PreviewDocument> documents, int enriched) {
    }

    static Result merge(
            List<ProductApiContract.PreviewDocument> base,
            List<ProductApiContract.PreviewDocument> overlay) {
        Map<String, ProductApiContract.PreviewDocument> byId = new HashMap<>();
        for (ProductApiContract.PreviewDocument document : overlay) {
            // 같은 번호가 두 번 오면 첫 건만 쓴다. 뒤엣것이 이기게 하면 페이지 경계에서
            // 결과가 흔들린다.
            byId.putIfAbsent(document.documentId(), document);
        }
        List<ProductApiContract.PreviewDocument> merged = new ArrayList<>(base.size());
        int enriched = 0;
        for (ProductApiContract.PreviewDocument document : base) {
            ProductApiContract.PreviewDocument extra = byId.get(document.documentId());
            if (extra == null) {
                merged.add(document);
                continue;
            }
            enriched++;
            merged.add(apply(document, extra));
        }
        return new Result(List.copyOf(merged), enriched);
    }

    /** BASE 필드가 이긴다. OVERLAY는 BASE에 없는 라벨 줄과, BASE가 비운 이미지만 채운다. */
    private static ProductApiContract.PreviewDocument apply(
            ProductApiContract.PreviewDocument base,
            ProductApiContract.PreviewDocument overlay) {
        Set<String> labels = labels(base.content());
        StringBuilder content = new StringBuilder(base.content());
        for (String line : overlay.content().split("\n")) {
            Matcher matcher = LABEL_LINE.matcher(line.strip());
            // add가 false면 BASE에 이미 있는 라벨이거나 OVERLAY 안에서 중복된 라벨이다.
            if (matcher.find() && labels.add(matcher.group(1))) {
                content.append('\n').append(line.strip());
            }
        }
        return new ProductApiContract.PreviewDocument(
                base.documentId(),
                base.title(),
                content.toString(),
                base.category(),
                base.sourceUrl(),
                base.sourceUpdatedAt().isAfter(overlay.sourceUpdatedAt())
                        ? base.sourceUpdatedAt() : overlay.sourceUpdatedAt(),
                base.imageUrl() == null ? overlay.imageUrl() : base.imageUrl());
    }

    private static Set<String> labels(String content) {
        Set<String> labels = new HashSet<>();
        for (String line : content.split("\n")) {
            Matcher matcher = LABEL_LINE.matcher(line.strip());
            if (matcher.find()) {
                labels.add(matcher.group(1));
            }
        }
        return labels;
    }
}
