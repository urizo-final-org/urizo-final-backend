package org.urizo.axmodulestudio.backend.orchestration.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Optional, immutable node settings. Legacy snapshots remain OFF. */
public record CodingInputOptions(boolean rtkSearchEnabled, boolean retainSmallToolResults) {
    public static final CodingInputOptions OFF = new CodingInputOptions(false, false);

    public static CodingInputOptions parse(String handler, JsonNode config) {
        Set<String> allowed = "coding.code".equals(handler)
                ? Set.of("rtkSearchEnabled", "retainSmallToolResults")
                : "coding.review".equals(handler) ? Set.of("rtkSearchEnabled") : Set.of();
        if (config == null || !config.isObject()) throw new IllegalArgumentException("Invalid node config");
        var fields = config.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!allowed.contains(field.getKey()) || !field.getValue().isBoolean()) {
                throw new IllegalArgumentException("Invalid input optimization config");
            }
        }
        return new CodingInputOptions(config.path("rtkSearchEnabled").asBoolean(false),
                config.path("retainSmallToolResults").asBoolean(false));
    }
}
