package org.urizo.axmodulestudio.backend.integration.ai.observability;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("local-full")
public final class LangfuseObservabilityService {

    static final String ENVIRONMENT = "local";
    private static final Duration MAX_RANGE = Duration.ofDays(31);
    private static final Duration SELECTED_PADDING = Duration.ofMinutes(1);
    private static final Duration MAX_SELECTED_RANGE = Duration.ofHours(24);
    private static final int SELECTED_ANCHOR_LIMIT = 2;
    private static final int SELECTED_CHILD_LIMIT = 50;
    private static final int MAX_CACHE_ENTRIES = 64;
    private static final String NODE_OBSERVATION_NAME = "axms.node";
    private static final Set<String> OBSERVATION_NAMES = Set.of(
            NODE_OBSERVATION_NAME, "axms.model", "axms.tool", "axms.check");
    private static final Set<String> SCORE_TYPES = Set.of(
            "NUMERIC", "BOOLEAN", "CATEGORICAL");
    private static final Set<String> SCORE_SOURCES = Set.of("API", "ANNOTATION", "EVAL");

    private final LangfuseProperties properties;
    private final LangfuseHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<CacheKey, TimedValue> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, TimedValue> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    public LangfuseObservabilityService(
            LangfuseProperties properties,
            LangfuseHttpTransport transport,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties are required");
        this.transport = Objects.requireNonNull(transport, "transport is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public MetricsResponse metrics(String from, String to) {
        return metrics(from, to, null);
    }

    public MetricsResponse metrics(String from, String to, String jobId) {
        TimeRange range = timeRange(from, to);
        String selectedJob = optionalJobId(jobId);
        Availability unavailable = configurationAvailability();
        if (unavailable != null) {
            return MetricsResponse.empty(unavailable, range);
        }
        CacheKey key = new CacheKey("metrics:" + selectedJob, range.from(), range.to());
        MetricsResponse cached = cached(key, MetricsResponse.class);
        if (cached != null) {
            return cached;
        }
        try {
            JsonNode root = request(metricsPath(range, selectedJob));
            MetricsResponse response = new MetricsResponse(
                    Availability.AVAILABLE, null, range.from(), range.to(), ENVIRONMENT,
                    data(root).stream().map(this::metricRow).toList());
            put(key, response);
            return response;
        }
        catch (UpstreamFailure failure) {
            return MetricsResponse.empty(Availability.UNAVAILABLE, range);
        }
    }

    public ObservationsResponse observations(String from, String to) {
        return observations(from, to, null, null, 50, "ALL");
    }

    public ObservationsResponse observations(
            String from, String to, String jobId, String cursor, int limit, String kind) {
        TimeRange range = timeRange(from, to);
        String selectedJob = optionalJobId(jobId);
        String pageCursor = optionalCursor(cursor);
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("Observation limit must be between 1 and 50.");
        }
        Set<String> names = switch (kind) {
            case "ALL" -> OBSERVATION_NAMES;
            case "NODE" -> Set.of(NODE_OBSERVATION_NAME, "axms.tool", "axms.check");
            case "PROVIDER" -> Set.of("axms.model");
            default -> throw new IllegalArgumentException("Unknown observation kind.");
        };
        Availability unavailable = configurationAvailability();
        if (unavailable != null) {
            return ObservationsResponse.empty(unavailable, range, limit);
        }
        String path = observationsPath(range, selectedJob, pageCursor, limit, names);
        CacheKey key = new CacheKey(path, range.from(), range.to());
        ObservationsResponse cached = cached(key, ObservationsResponse.class);
        if (cached != null) {
            return cached;
        }
        try {
            JsonNode root = request(path);
            List<ObservationRow> rows = data(root).stream()
                    .filter(row -> names.contains(row.path("name").asText()))
                    .map(this::observationRow)
                    .filter(row -> selectedJob == null || selectedJob.equals(row.metadata().jobId()))
                    .toList();
            String nextCursor = nullableText(root.path("meta"), "cursor");
            if (nextCursor != null && (nextCursor.isBlank() || !validCursor(nextCursor)
                    || nextCursor.equals(pageCursor))) {
                throw new UpstreamFailure();
            }
            ObservationsResponse response = new ObservationsResponse(
                    Availability.AVAILABLE, null, range.from(), range.to(), ENVIRONMENT,
                    rows, nextCursor, limit);
            put(key, response);
            return response;
        }
        catch (UpstreamFailure failure) {
            return ObservationsResponse.empty(Availability.UNAVAILABLE, range, limit);
        }
    }

    public SelectedObservationsResponse selectedObservations(
            String jobId,
            String traceId,
            String profileVersionId,
            int pipelineAttempt,
            int executionAttempt,
            String nodeId,
            long nodeSequence,
            String observationTraceId,
            Instant startedAt,
            Instant lastReportedAt) {
        TimeRange range = selectedRange(startedAt, lastReportedAt);
        SelectedObservationKey selection = new SelectedObservationKey(
                jobId, traceId, profileVersionId, pipelineAttempt, executionAttempt,
                nodeId, nodeSequence, observationTraceId);
        if (observationTraceId == null) {
            return SelectedObservationsResponse.empty(
                    Availability.UNCONNECTED, selection, range, false);
        }
        Availability unavailable = configurationAvailability();
        if (unavailable != null) {
            return SelectedObservationsResponse.empty(
                    unavailable, selection, range, false);
        }
        CacheKey key = new CacheKey("selected:" + selection, range.from(), range.to());
        SelectedObservationsResponse cached = cached(
                key, SelectedObservationsResponse.class);
        if (cached != null) {
            return cached;
        }
        try {
            JsonNode anchorRoot = request(selectedAnchorPath(range, selection));
            List<ObservationRow> anchors = data(anchorRoot).stream()
                    .filter(row -> NODE_OBSERVATION_NAME.equals(row.path("name").asText()))
                    .map(this::observationRow)
                    .filter(row -> matchesOccurrence(row.metadata(), selection))
                    .toList();
            boolean anchorTruncated = hasCursor(anchorRoot);
            if (anchors.size() != 1 || anchorTruncated) {
                SelectedObservationsResponse response = SelectedObservationsResponse.empty(
                        Availability.UNCONNECTED, selection, range, anchorTruncated);
                put(key, response);
                return response;
            }
            ObservationRow anchor = anchors.get(0);
            JsonNode childRoot = request(selectedChildrenPath(
                    range, selection, anchor.id()));
            List<ObservationRow> children = data(childRoot).stream()
                    .filter(row -> OBSERVATION_NAMES.contains(row.path("name").asText()))
                    .map(this::observationRow)
                    .filter(row -> anchor.id().equals(row.parentObservationId()))
                    .filter(row -> matchesBusinessIdentity(row.metadata(), selection))
                    .toList();
            List<ObservationRow> rows = new java.util.ArrayList<>(children.size() + 1);
            rows.add(anchor);
            rows.addAll(children);
            SelectedObservationsResponse response = new SelectedObservationsResponse(
                    Availability.AVAILABLE, null, selection.jobId(), selection.traceId(),
                    selection.observationTraceId(), selection.profileVersionId(),
                    selection.pipelineAttempt(), selection.executionAttempt(),
                    selection.nodeId(), selection.nodeSequence(), range.from(), range.to(),
                    ENVIRONMENT, List.copyOf(rows), hasCursor(childRoot));
            put(key, response);
            return response;
        }
        catch (UpstreamFailure failure) {
            return SelectedObservationsResponse.empty(
                    Availability.UNAVAILABLE, selection, range, false);
        }
    }

    public ScoresResponse scores(String from, String to) {
        TimeRange range = timeRange(from, to);
        Availability unavailable = configurationAvailability();
        if (unavailable != null) {
            return ScoresResponse.empty(unavailable, range);
        }
        CacheKey key = new CacheKey("scores", range.from(), range.to());
        ScoresResponse cached = cached(key, ScoresResponse.class);
        if (cached != null) {
            return cached;
        }
        try {
            JsonNode root = request(scoresPath(range));
            List<ScoreRow> rows = data(root).stream()
                    .filter(LangfuseObservabilityService::allowedScore)
                    .map(this::scoreRow)
                    .toList();
            ScoresResponse response = new ScoresResponse(
                    Availability.AVAILABLE, null, range.from(), range.to(), ENVIRONMENT,
                    rows);
            put(key, response);
            return response;
        }
        catch (UpstreamFailure failure) {
            return ScoresResponse.empty(Availability.UNAVAILABLE, range);
        }
    }

    private Availability configurationAvailability() {
        if (properties.configured()) {
            return null;
        }
        return Availability.DISABLED;
    }

    private JsonNode request(String pathAndQuery) {
        String credentials = properties.publicKey() + ":" + properties.secretKey();
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
        try {
            LangfuseHttpTransport.Response response = transport.get(
                    properties.endpoint(pathAndQuery),
                    Map.of("Accept", "application/json", "Authorization", authorization),
                    properties.requestTimeout(),
                    properties.maxResponseBytes());
            if (response.statusCode() != 200) {
                throw new UpstreamFailure();
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root == null || !root.isObject() || !root.path("data").isArray()) {
                throw new UpstreamFailure();
            }
            return root;
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new UpstreamFailure();
        }
        catch (IOException | RuntimeException failure) {
            throw new UpstreamFailure();
        }
    }

    private String metricsPath(TimeRange range, String jobId) {
        ObjectNode query = objectMapper.createObjectNode();
        query.put("view", "observations");
        ArrayNode dimensions = query.putArray("dimensions");
        dimensions.addObject().put("field", "providedModelName");
        ArrayNode metrics = query.putArray("metrics");
        metric(metrics, "count", "count");
        metric(metrics, "inputTokens", "sum");
        metric(metrics, "outputTokens", "sum");
        metric(metrics, "totalTokens", "sum");
        metric(metrics, "totalCost", "sum");
        metric(metrics, "latency", "p50");
        metric(metrics, "latency", "p95");
        ArrayNode filters = query.putArray("filters");
        ObjectNode environment = filters.addObject();
        environment.put("column", "environment");
        environment.put("operator", "any of");
        environment.putArray("value").add(ENVIRONMENT);
        environment.put("type", "stringOptions");
        fixedStringFilter(filters, "name", "axms.model");
        fixedStringFilter(filters, "type", "GENERATION");
        if (jobId != null) metadataFilter(filters, "jobId", jobId);
        query.put("fromTimestamp", range.from().toString());
        query.put("toTimestamp", range.to().toString());
        ObjectNode order = query.putArray("orderBy").addObject();
        order.put("field", "sum_totalCost");
        order.put("direction", "desc");
        query.putObject("config").put("row_limit", 50);
        try {
            return "/api/public/v2/metrics?query=" + encode(objectMapper.writeValueAsString(query));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("The fixed Langfuse metrics query cannot be encoded.");
        }
    }

    private static void metric(ArrayNode metrics, String measure, String aggregation) {
        ObjectNode metric = metrics.addObject();
        metric.put("measure", measure);
        metric.put("aggregation", aggregation);
    }

    private static void fixedStringFilter(ArrayNode filters, String column, String value) {
        ObjectNode filter = filters.addObject();
        filter.put("column", column);
        filter.put("operator", "=");
        filter.put("value", value);
        filter.put("type", "string");
    }

    private String observationsPath(
            TimeRange range, String jobId, String cursor, int limit, Set<String> names) {
        // The advanced filter overrides flat parameters: keep every constraint in it.
        ArrayNode filters = objectMapper.createArrayNode();
        filter(filters, "string", "environment", null, "=", ENVIRONMENT);
        filter(filters, "datetime", "startTime", null, ">=", range.from().toString());
        filter(filters, "datetime", "startTime", null, "<", range.to().toString());
        ObjectNode nameFilter = filters.addObject();
        nameFilter.put("type", "stringOptions");
        nameFilter.put("column", "name");
        nameFilter.put("operator", "any of");
        ArrayNode nameValues = nameFilter.putArray("value");
        names.stream().sorted().forEach(nameValues::add);
        if (jobId != null) metadataFilter(filters, "jobId", jobId);
        return selectedPath(filters, limit) + (cursor == null ? "" : "&cursor=" + encode(cursor));
    }

    private static String optionalJobId(String jobId) {
        if (jobId == null || jobId.isBlank()) return null;
        String value = jobId.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Job ID must be a full UUID.");
        }
        return value;
    }

    private static String optionalCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) return null;
        if (!validCursor(cursor)) {
            throw new IllegalArgumentException("Invalid observation cursor.");
        }
        return cursor;
    }

    private static boolean validCursor(String cursor) {
        return cursor.length() <= 2048 && cursor.matches("[A-Za-z0-9_+/=-]+");
    }

    private String selectedAnchorPath(
            TimeRange range, SelectedObservationKey selection) {
        ArrayNode filters = objectMapper.createArrayNode();
        commonSelectedFilters(filters, range, selection);
        filter(filters, "string", "name", null, "=", NODE_OBSERVATION_NAME);
        metadataFilter(filters, "jobId", selection.jobId());
        metadataFilter(filters, "profileVersionId", selection.profileVersionId());
        metadataFilter(filters, "nodeId", selection.nodeId());
        metadataFilter(filters, "pipelineAttempt",
                Integer.toString(selection.pipelineAttempt()));
        metadataFilter(filters, "executionAttempt",
                Integer.toString(selection.executionAttempt()));
        metadataFilter(filters, "nodeSequence", Long.toString(selection.nodeSequence()));
        return selectedPath(filters, SELECTED_ANCHOR_LIMIT);
    }

    private String selectedChildrenPath(
            TimeRange range, SelectedObservationKey selection, String parentObservationId) {
        ArrayNode filters = objectMapper.createArrayNode();
        commonSelectedFilters(filters, range, selection);
        filter(filters, "string", "parentObservationId", null, "=", parentObservationId);
        return selectedPath(filters, SELECTED_CHILD_LIMIT);
    }

    private static void commonSelectedFilters(
            ArrayNode filters, TimeRange range, SelectedObservationKey selection) {
        filter(filters, "string", "traceId", null, "=", selection.observationTraceId());
        filter(filters, "string", "environment", null, "=", ENVIRONMENT);
        filter(filters, "datetime", "startTime", null, ">=", range.from().toString());
        filter(filters, "datetime", "startTime", null, "<", range.to().toString());
    }

    private String selectedPath(ArrayNode filters, int limit) {
        try {
            return "/api/public/v2/observations"
                    + "?fields=core%2Cbasic%2Cmetadata%2Cmodel%2Cusage%2Cmetrics"
                    + "&limit=" + limit
                    + "&filter=" + encode(objectMapper.writeValueAsString(filters));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "The selected Langfuse observation query cannot be encoded.");
        }
    }

    private static void metadataFilter(ArrayNode filters, String key, String value) {
        filter(filters, "stringObject", "metadata", key, "=", value);
    }

    private static void filter(
            ArrayNode filters,
            String type,
            String column,
            String key,
            String operator,
            String value) {
        ObjectNode filter = filters.addObject();
        filter.put("type", type);
        filter.put("column", column);
        if (key != null) {
            filter.put("key", key);
        }
        filter.put("operator", operator);
        filter.put("value", value);
    }

    private static String scoresPath(TimeRange range) {
        return "/api/public/v3/scores?limit=50&environment=local"
                + "&fromTimestamp=" + encode(range.from().toString())
                + "&toTimestamp=" + encode(range.to().toString());
    }

    private MetricRow metricRow(JsonNode row) {
        requireObject(row);
        return new MetricRow(
                nullableText(row, "providedModelName"),
                nullableLong(row, "count_count"),
                nullableLong(row, "sum_inputTokens"),
                nullableLong(row, "sum_outputTokens"),
                nullableLong(row, "sum_totalTokens"),
                nullableDecimal(row, "sum_totalCost"),
                nullableDecimal(row, "p50_latency"),
                nullableDecimal(row, "p95_latency"));
    }

    private ObservationRow observationRow(JsonNode row) {
        requireObject(row);
        String environment = requiredText(row, "environment");
        if (!ENVIRONMENT.equals(environment)) {
            throw new UpstreamFailure();
        }
        JsonNode usageDetails = optionalObject(row, "usageDetails");
        BigDecimal latencySeconds = nullableDecimal(row, "latency");
        return new ObservationRow(
                requiredText(row, "id"),
                requiredText(row, "traceId"),
                nullableText(row, "parentObservationId"),
                requiredText(row, "type"),
                requiredText(row, "name"),
                nullableText(row, "level"),
                environment,
                requiredInstant(row, "startTime"),
                nullableInstant(row, "endTime"),
                nullableText(row, "model"),
                nullableLong(usageDetails, "input"),
                nullableLong(usageDetails, "output"),
                latencySeconds == null ? null : latencySeconds.movePointRight(3),
                metadata(row.path("metadata")));
    }

    private ScoreRow scoreRow(JsonNode row) {
        requireObject(row);
        String environment = requiredText(row, "environment");
        String dataType = requiredText(row, "dataType").toUpperCase(Locale.ROOT);
        String source = requiredText(row, "source").toUpperCase(Locale.ROOT);
        JsonNode value = row.path("value");
        if (!ENVIRONMENT.equals(environment)
                || !SCORE_TYPES.contains(dataType)
                || !SCORE_SOURCES.contains(source)
                || !typedScoreValue(dataType, value)) {
            throw new UpstreamFailure();
        }
        return new ScoreRow(
                requiredText(row, "id"),
                requiredText(row, "name"),
                value.deepCopy(),
                dataType,
                source,
                requiredInstant(row, "timestamp"),
                environment);
    }

    private static boolean allowedScore(JsonNode row) {
        requireObject(row);
        return SCORE_TYPES.contains(requiredText(row, "dataType").toUpperCase(Locale.ROOT));
    }

    private static boolean typedScoreValue(String dataType, JsonNode value) {
        return switch (dataType) {
            case "NUMERIC" -> value.isNumber();
            case "BOOLEAN" -> value.isBoolean();
            case "CATEGORICAL" -> value.isTextual();
            default -> false;
        };
    }

    private static ObservationMetadata metadata(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return new ObservationMetadata(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    null, null);
        }
        requireObject(value);
        return new ObservationMetadata(
                nullableText(value, "jobId"),
                nullableText(value, "traceId"),
                nullableText(value, "profileVersionId"),
                nullableText(value, "nodeId"),
                nullableText(value, "nodeType"),
                nullableText(value, "nodeStatus"),
                nullableInteger(value, "attempt"),
                identityInteger(value, "pipelineAttempt"),
                identityInteger(value, "executionAttempt"),
                identityLong(value, "nodeSequence"),
                nullableText(value, "provider"),
                nullableText(value, "model"),
                nullableInteger(value, "inputTokens"),
                nullableInteger(value, "outputTokens"),
                nullableLong(value, "latencyMs"),
                nullableText(value, "errorCode"),
                nullableText(value, "toolStatus"),
                nullableText(value, "checkStatus"));
    }

    private static boolean matchesOccurrence(
            ObservationMetadata metadata, SelectedObservationKey selection) {
        return matchesBusinessIdentity(metadata, selection)
                && Integer.valueOf(selection.pipelineAttempt()).equals(
                        metadata.pipelineAttempt())
                && Integer.valueOf(selection.executionAttempt()).equals(
                        metadata.executionAttempt())
                && Long.valueOf(selection.nodeSequence()).equals(metadata.nodeSequence());
    }

    private static boolean matchesBusinessIdentity(
            ObservationMetadata metadata, SelectedObservationKey selection) {
        return selection.jobId().equals(metadata.jobId())
                && selection.traceId().equals(metadata.traceId())
                && selection.profileVersionId().equals(metadata.profileVersionId())
                && selection.nodeId().equals(metadata.nodeId());
    }

    private static TimeRange selectedRange(Instant startedAt, Instant lastReportedAt) {
        Instant start = startedAt == null ? lastReportedAt : startedAt;
        if (start == null || lastReportedAt == null) {
            throw new IllegalArgumentException("The selected occurrence has no valid time range.");
        }
        Instant from = start.minus(SELECTED_PADDING);
        Instant uncappedTo = lastReportedAt.plus(SELECTED_PADDING);
        Instant maximumTo = from.plus(MAX_SELECTED_RANGE);
        Instant to = uncappedTo.isAfter(maximumTo) ? maximumTo : uncappedTo;
        if (!to.isAfter(from)) {
            to = from.plus(SELECTED_PADDING);
        }
        return new TimeRange(from, to);
    }

    private static TimeRange timeRange(String from, String to) {
        Instant fromInstant = parseUtc(from, "from");
        Instant toInstant = parseUtc(to, "to");
        Duration duration = Duration.between(fromInstant, toInstant);
        if (duration.isZero() || duration.isNegative() || duration.compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException(
                    "The observability range must be greater than zero and at most 31 days.");
        }
        return new TimeRange(fromInstant, toInstant);
    }

    private static Instant parseUtc(String value, String field) {
        if (value == null || !value.endsWith("Z")) {
            throw new IllegalArgumentException(field + " must be a UTC timestamp ending in Z.");
        }
        try {
            return Instant.parse(value);
        }
        catch (DateTimeParseException failure) {
            throw new IllegalArgumentException(field + " must be a valid UTC timestamp.");
        }
    }

    private static List<JsonNode> data(JsonNode root) {
        return java.util.stream.StreamSupport.stream(root.path("data").spliterator(), false)
                .toList();
    }

    private static boolean hasCursor(JsonNode root) {
        return root.path("meta").path("cursor").isTextual();
    }

    private static void requireObject(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new UpstreamFailure();
        }
    }

    private static JsonNode optionalObject(JsonNode value, String field) {
        JsonNode node = value.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        requireObject(node);
        return node;
    }

    private static String requiredText(JsonNode value, String field) {
        String result = nullableText(value, field);
        if (result == null || result.isBlank()) {
            throw new UpstreamFailure();
        }
        return result;
    }

    private static String nullableText(JsonNode value, String field) {
        JsonNode node = value.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new UpstreamFailure();
        }
        return node.textValue();
    }

    private static Long nullableLong(JsonNode value, String field) {
        if (value == null) {
            return null;
        }
        JsonNode node = value.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
            throw new UpstreamFailure();
        }
        return node.longValue();
    }

    private static Integer nullableInteger(JsonNode value, String field) {
        Long result = nullableLong(value, field);
        if (result == null) {
            return null;
        }
        if (result > Integer.MAX_VALUE) {
            throw new UpstreamFailure();
        }
        return result.intValue();
    }

    private static Integer identityInteger(JsonNode value, String field) {
        Long result = identityLong(value, field);
        if (result == null) {
            return null;
        }
        if (result > Integer.MAX_VALUE) {
            throw new UpstreamFailure();
        }
        return result.intValue();
    }

    private static Long identityLong(JsonNode value, String field) {
        JsonNode node = value.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 1) {
            return node.longValue();
        }
        if (node.isTextual() && node.textValue().matches("[1-9][0-9]{0,18}")) {
            try {
                return Long.parseLong(node.textValue());
            }
            catch (NumberFormatException failure) {
                throw new UpstreamFailure();
            }
        }
        throw new UpstreamFailure();
    }

    private static BigDecimal nullableDecimal(JsonNode value, String field) {
        JsonNode node = value.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isNumber() || node.decimalValue().signum() < 0) {
            throw new UpstreamFailure();
        }
        return node.decimalValue();
    }

    private static Instant requiredInstant(JsonNode value, String field) {
        Instant result = nullableInstant(value, field);
        if (result == null) {
            throw new UpstreamFailure();
        }
        return result;
    }

    private static Instant nullableInstant(JsonNode value, String field) {
        String text = nullableText(value, field);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        }
        catch (DateTimeParseException failure) {
            throw new UpstreamFailure();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private synchronized <T> T cached(CacheKey key, Class<T> type) {
        TimedValue value = cache.get(key);
        if (value == null || !clock.instant().isBefore(value.expiresAt())) {
            cache.remove(key);
            return null;
        }
        return type.cast(value.value());
    }

    private synchronized void put(CacheKey key, Object value) {
        cache.put(key, new TimedValue(value, clock.instant().plus(properties.cacheTtl())));
    }

    public enum Availability {
        AVAILABLE,
        UNCONNECTED,
        DISABLED,
        UNAVAILABLE
    }

    public record MetricsResponse(
            Availability status,
            String errorCode,
            Instant from,
            Instant to,
            String environment,
            List<MetricRow> rows) {

        static MetricsResponse empty(Availability status, TimeRange range) {
            return new MetricsResponse(status, LangfuseObservabilityService.errorCode(status),
                    range.from(), range.to(),
                    ENVIRONMENT, List.of());
        }
    }

    public record MetricRow(
            String model,
            Long observationCount,
            Long inputTokens,
            Long outputTokens,
            Long totalTokens,
            BigDecimal totalCost,
            BigDecimal p50LatencyMs,
            BigDecimal p95LatencyMs) { }

    public record ObservationsResponse(
            Availability status,
            String errorCode,
            Instant from,
            Instant to,
            String environment,
            List<ObservationRow> observations,
            String nextCursor,
            int limit) {

        public ObservationsResponse(Availability status, String errorCode, Instant from,
                Instant to, String environment, List<ObservationRow> observations) {
            this(status, errorCode, from, to, environment, observations, null, 50);
        }

        static ObservationsResponse empty(Availability status, TimeRange range, int limit) {
            return new ObservationsResponse(status,
                    LangfuseObservabilityService.errorCode(status), range.from(), range.to(),
                    ENVIRONMENT, List.of(), null, limit);
        }
    }

    public record ObservationRow(
            String id,
            String traceId,
            String parentObservationId,
            String type,
            String name,
            String level,
            String environment,
            Instant startTime,
            Instant endTime,
            String model,
            Long inputTokens,
            Long outputTokens,
            BigDecimal latencyMs,
            ObservationMetadata metadata) { }

    public record ObservationMetadata(
            String jobId,
            String traceId,
            String profileVersionId,
            String nodeId,
            String nodeType,
            String nodeStatus,
            Integer attempt,
            Integer pipelineAttempt,
            Integer executionAttempt,
            Long nodeSequence,
            String provider,
            String model,
            Integer inputTokens,
            Integer outputTokens,
            Long latencyMs,
            String errorCode,
            String toolStatus,
            String checkStatus) { }

    public record SelectedObservationsResponse(
            Availability status,
            String errorCode,
            String jobId,
            String traceId,
            String observationTraceId,
            String profileVersionId,
            int pipelineAttempt,
            int executionAttempt,
            String nodeId,
            long nodeSequence,
            Instant from,
            Instant to,
            String environment,
            List<ObservationRow> observations,
            boolean truncated) {

        static SelectedObservationsResponse empty(
                Availability status,
                SelectedObservationKey selection,
                TimeRange range,
                boolean truncated) {
            return new SelectedObservationsResponse(
                    status, LangfuseObservabilityService.errorCode(status),
                    selection.jobId(), selection.traceId(), selection.observationTraceId(),
                    selection.profileVersionId(),
                    selection.pipelineAttempt(), selection.executionAttempt(),
                    selection.nodeId(), selection.nodeSequence(), range.from(), range.to(),
                    ENVIRONMENT, List.of(), truncated);
        }
    }

    public record ScoresResponse(
            Availability status,
            String errorCode,
            Instant from,
            Instant to,
            String environment,
            List<ScoreRow> scores) {

        static ScoresResponse empty(Availability status, TimeRange range) {
            return new ScoresResponse(status, LangfuseObservabilityService.errorCode(status),
                    range.from(), range.to(),
                    ENVIRONMENT, List.of());
        }
    }

    public record ScoreRow(
            String id,
            String name,
            JsonNode value,
            String dataType,
            String source,
            Instant timestamp,
            String environment) {

        public ScoreRow {
            value = value.deepCopy();
        }

        @Override
        public JsonNode value() {
            return value.deepCopy();
        }
    }

    private static String errorCode(Availability status) {
        return switch (status) {
            case AVAILABLE -> null;
            case UNCONNECTED -> "OBSERVATION_NOT_CONNECTED";
            case DISABLED -> "LANGFUSE_DISABLED";
            case UNAVAILABLE -> "LANGFUSE_UPSTREAM_UNAVAILABLE";
        };
    }

    private record TimeRange(Instant from, Instant to) { }
    private record SelectedObservationKey(
            String jobId,
            String traceId,
            String profileVersionId,
            int pipelineAttempt,
            int executionAttempt,
            String nodeId,
            long nodeSequence,
            String observationTraceId) { }
    private record CacheKey(String kind, Instant from, Instant to) { }
    private record TimedValue(Object value, Instant expiresAt) { }
    private static final class UpstreamFailure extends RuntimeException { }
}
