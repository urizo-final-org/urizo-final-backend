package org.urizo.axmodulestudio.backend.coding.modelturn;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class CodingModelTurnRequestDigester {

    private final ObjectMapper objectMapper;

    CodingModelTurnRequestDigester(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    byte[] digest(CodingModelTurnContract.Request request) {
        byte[] canonical = null;
        try {
            canonical = objectMapper.writeValueAsBytes(canonicalize(objectMapper.valueToTree(request)));
            return MessageDigest.getInstance("SHA-256").digest(canonical);
        }
        catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new CodingModelTurnAccessException(
                    "INTERNAL_TRANSIENT_ERROR",
                    "Model Turn request digest is unavailable.",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    true,
                    1_000L);
        }
        finally {
            if (canonical != null) {
                Arrays.fill(canonical, (byte) 0);
            }
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                result.set(name, canonicalize(value.get(name)));
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return value.deepCopy();
    }
}
