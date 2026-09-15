package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.TreeMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class SearchCodeModelViewTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static ObjectNode fixture() {
        ObjectNode source = JSON.createObjectNode().put("query", "한글 🔍")
                .put("scope", "src").put("truncated", true);
        var matches = source.putArray("matches");
        for (int i = 0; i < 50; i++) {
            matches.addObject().put("path", "src/긴 경로/" + (i % 2) + ".java")
                    .put("line", i + 1).put("column", 2)
                    .put("preview", "  \t한글 👋 \"literal\"  " + "x".repeat(100) + "  ");
        }
        return source;
    }

    @Test
    void preservesEveryMatchAndOriginalOrderIncludingWhitespaceAndUnicode() throws Exception {
        ObjectNode source = fixture();
        for (String handler : new String[]{"coding.code", "coding.review"}) {
            String compact = SearchCodeModelView.render(handler, "search_code", source.toString());
            assertThat(compact.length()).isLessThan(source.toString().length());
            JsonNode view = JSON.readTree(compact);
            ObjectNode restored = JSON.createObjectNode();
            restored.set("query", view.get("query"));
            restored.set("scope", view.get("scope"));
            restored.set("truncated", view.get("truncated"));
            TreeMap<Integer, JsonNode> matches = new TreeMap<>();
            view.get("groups").fields().forEachRemaining(group -> {
                for (JsonNode row : group.getValue()) {
                    matches.put(row.get(0).intValue(), JSON.createObjectNode()
                            .put("path", group.getKey()).put("line", row.get(1).intValue())
                            .put("column", row.get(2).intValue()).put("preview", row.get(3).textValue()));
                }
            });
            restored.putArray("matches").addAll(matches.values());
            assertThat(restored).isEqualTo(source);
            assertThat(SearchCodeModelView.render(handler, "search_code", compact)).isEqualTo(compact);
        }
    }

    @Test
    void leavesOtherStagesAndToolsAndSmallResultsUntouched() {
        String raw = fixture().toString();
        assertThat(SearchCodeModelView.render("coding.analyze", "search_code", raw)).isSameAs(raw);
        for (String tool : new String[]{"read_file", "read_diff", "apply_patch"}) {
            assertThat(SearchCodeModelView.render("coding.code", tool, raw)).isSameAs(raw);
        }
        String small = "{\"query\":\"x\",\"scope\":\"src\",\"matches\":[],\"truncated\":false}";
        assertThat(SearchCodeModelView.render("coding.code", "search_code", small)).isSameAs(small);
    }

    @Test
    void fallsBackForUnknownFieldsInvalidCoordinatesAndMalformedOrAmbiguousJson() {
        ObjectNode extraRoot = fixture().put("future", true);
        ObjectNode extraMatch = fixture();
        ((ObjectNode) extraMatch.get("matches").get(0)).put("score", 1);
        ObjectNode badCoordinate = fixture();
        ((ObjectNode) badCoordinate.get("matches").get(0)).put("line", -1);
        ObjectNode empty = fixture();
        empty.put("query", "x".repeat(5000));
        empty.putArray("matches");
        for (String raw : new String[]{extraRoot.toString(), extraMatch.toString(), badCoordinate.toString(),
                empty.toString(), fixture() + "{}", "{" + " ".repeat(5000),
                fixture().toString().replace("\"scope\":\"src\"", "\"scope\":\"src\",\"scope\":\"other\"")}) {
            assertThat(SearchCodeModelView.render("coding.code", "search_code", raw)).isSameAs(raw);
        }
    }

    @Test
    void includesReadingGuideCostInTheSizeFallback() {
        ObjectNode source = JSON.createObjectNode().put("query", "x")
                .put("scope", "src").put("truncated", false);
        var matches = source.putArray("matches");
        for (int i = 0; i < 4; i++) {
            matches.addObject().put("path", "a").put("line", i + 1).put("column", 1)
                    .put("preview", "x".repeat(1200));
        }
        String raw = source.toString();
        assertThat(raw.length()).isGreaterThan(4096);
        // Repeated keys alone can shrink, but the full reading guide makes this case larger.
        assertThat(SearchCodeModelView.render("coding.code", "search_code", raw)).isSameAs(raw);
        assertThat(SearchCodeModelView.render("coding.review", "search_code", raw)).isSameAs(raw);
    }

    @Test
    void fallsBackWhenGroupingWouldIncreaseBytes() {
        ObjectNode one = fixture();
        one.putArray("matches").addObject().put("path", "a").put("line", 1)
                .put("column", 1).put("preview", "x".repeat(5000));
        String raw = one.toString();
        assertThat(SearchCodeModelView.render("coding.code", "search_code", raw)).isSameAs(raw);
    }
}
