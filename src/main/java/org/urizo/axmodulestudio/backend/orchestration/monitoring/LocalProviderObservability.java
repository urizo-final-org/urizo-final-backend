package org.urizo.axmodulestudio.backend.orchestration.monitoring;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.integration.ai.observability.LangfuseObservabilityService.ObservationMetadata;
import org.urizo.axmodulestudio.backend.integration.ai.observability.LangfuseObservabilityService.ObservationRow;

/** Local gateway and imported observations, deduplicated in SQL. Never requests Langfuse or estimates missing usage/cost. */
@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class LocalProviderObservability {
    private final JdbcTemplate jdbc;
    public LocalProviderObservability(@Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String TOTALS = """
            count(*) AS calls, sum(input_tokens) AS input_tokens, sum(output_tokens) AS output_tokens,
            sum(input_tokens + output_tokens) AS total_tokens,
            count(input_tokens) AS input_known, count(output_tokens) AS output_known,
            count(input_tokens + output_tokens) AS total_known
            """;

    public Metrics metrics(String from, String to, String jobId) {
        Range range = range(from, to, jobId);
        var rows = jdbc.query("SELECT model, " + TOTALS + """
                , percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (finished_at-started_at))*1000)
                    FILTER (WHERE finished_at >= started_at) AS p50,
                  percentile_cont(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (finished_at-started_at))*1000)
                    FILTER (WHERE finished_at >= started_at) AS p95
                FROM app.ai_provider_observation_read
                """ + range.where() + " GROUP BY model ORDER BY calls DESC, model LIMIT 51",
                (rs, n) -> new Metric(rs.getString("model"), rs.getLong("calls"), number(rs, "input_tokens"),
                        number(rs, "output_tokens"), number(rs, "total_tokens"), null,
                        rs.getBigDecimal("p50"), rs.getBigDecimal("p95"), rs.getLong("input_known"),
                        rs.getLong("output_known"), rs.getLong("total_known")), range.args());
        return new Metrics("AVAILABLE", null, range.from, range.to, "local", "LOCAL_DB",
                List.copyOf(rows.subList(0, Math.min(50, rows.size()))), rows.size() > 50);
    }

    public Tokens tokenUsage(String from, String to, String jobId) {
        Range range = range(from, to, jobId);
        ChronoUnit unit = Duration.between(range.from, range.to).compareTo(Duration.ofHours(48)) <= 0
                ? ChronoUnit.HOURS : ChronoUnit.DAYS;
        String granularity = unit == ChronoUnit.HOURS ? "hour" : "day";
        var rows = jdbc.query("SELECT date_trunc('" + granularity
                + "', started_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS bucket, " + TOTALS
                + " FROM app.ai_provider_observation_read" + range.where() + " GROUP BY bucket ORDER BY bucket",
                (rs, n) -> new Point(rs.getTimestamp("bucket").toInstant(), number(rs, "input_tokens"),
                        number(rs, "output_tokens"), number(rs, "total_tokens"), rs.getLong("calls"),
                        rs.getLong("input_known"), rs.getLong("output_known"), rs.getLong("total_known")), range.args());
        Map<Instant, Point> buckets = new TreeMap<>();
        rows.forEach(row -> buckets.put(row.bucketStart, row));
        List<Point> points = new ArrayList<>();
        if (!rows.isEmpty()) {
            for (Instant at = range.from.truncatedTo(unit); at.isBefore(range.to); at = at.plus(1, unit)) {
                points.add(buckets.getOrDefault(at, new Point(at, null, null, null, 0, 0, 0, 0)));
            }
        }
        return new Tokens("AVAILABLE", null, range.from, range.to, "local", "LOCAL_DB", granularity, List.copyOf(points));
    }

    public Calls observations(String from, String to, String jobId, String cursor, int limit) {
        Range range = range(from, to, jobId);
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Call limit must be between 1 and 50.");
        Instant beforeTime = null;
        String beforeId = null;
        if (cursor != null) {
            try {
                if (cursor.length() > 256) throw new IllegalArgumentException();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 2 || !parts[1].matches("(?:lf:)?[A-Za-z0-9-]{1,64}")) throw new IllegalArgumentException();
                beforeTime = Instant.parse(parts[0]); beforeId = parts[1];
            }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid local call cursor."); }
        }
        List<Object> args = new ArrayList<>(List.of(range.args()));
        if (beforeTime != null) { args.add(Timestamp.from(beforeTime)); args.add(beforeId); }
        args.add(limit + 1);
        var rows = jdbc.query("""
                SELECT * FROM app.ai_provider_observation_read
                """ + range.where() + (beforeTime == null ? "" : " AND (started_at, call_id) < (?, ?)")
                + " ORDER BY started_at DESC, call_id DESC LIMIT ?", (rs, n) -> call(rs), args.toArray());
        boolean more = rows.size() > limit;
        var page = rows.subList(0, Math.min(limit, rows.size()));
        return new Calls("AVAILABLE", null, range.from, range.to, "local", "LOCAL_DB",
                List.copyOf(page), more ? cursorFor(page.get(page.size() - 1)) : null, limit);
    }

    private static String cursorFor(ObservationRow row) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (row.startTime() + "|" + row.id()).getBytes(StandardCharsets.UTF_8));
    }

    private static ObservationRow call(ResultSet rs) throws SQLException {
        Instant start = rs.getTimestamp("started_at").toInstant();
        Timestamp finished = rs.getTimestamp("finished_at");
        Instant end = finished == null ? null : finished.toInstant();
        BigDecimal latency = end == null || end.isBefore(start) ? null
                : BigDecimal.valueOf(Duration.between(start, end).toNanos(), 6);
        Long input = number(rs, "input_tokens"), output = number(rs, "output_tokens"), cached = number(rs, "cached_input_tokens");
        String trace = rs.getString("observation_trace_id");
        var metadata = new ObservationMetadata(rs.getString("job_id"), null, rs.getString("profile_version_id"),
                rs.getString("node_id"), "agent", null, (Integer) rs.getObject("provider_attempt"),
                (Integer) rs.getObject("pipeline_attempt"), (Integer) rs.getObject("execution_attempt"), (Long) rs.getObject("node_sequence"),
                rs.getString("provider"), rs.getString("model"), null, null,
                latency == null ? null : latency.longValue(), rs.getString("error_code"), null, null);
        return new ObservationRow(rs.getString("call_id"), trace, null, "GENERATION", "axms.model",
                "FAILED".equals(rs.getString("status")) ? "ERROR" : "DEFAULT", "local", start, end,
                rs.getString("model"), input, output, latency, metadata, cached,
                input != null && cached != null ? input - cached : null, cached == null ? "NOT_REPORTED" : "REPORTED");
    }

    static Range range(String from, String to, String jobId) {
        try {
            Instant start = Instant.parse(from), end = Instant.parse(to);
            if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofDays(31)) > 0)
                throw new IllegalArgumentException("Invalid range");
            UUID job = jobId == null || jobId.isBlank() ? null : UUID.fromString(jobId);
            if (job != null && !job.toString().equalsIgnoreCase(jobId)) throw new IllegalArgumentException("Invalid Job ID");
            return new Range(start, end, job);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Use a valid UTC range of at most 31 days and a full Job UUID.");
        }
    }

    private static Long number(ResultSet rs, String key) throws SQLException {
        long value = rs.getLong(key); return rs.wasNull() ? null : value;
    }
    record Range(Instant from, Instant to, UUID job) {
        String where() { return " WHERE started_at >= ? AND started_at < ?" + (job == null ? "" : " AND job_id = ?"); }
        Object[] args() { return job == null ? new Object[] {Timestamp.from(from), Timestamp.from(to)}
                : new Object[] {Timestamp.from(from), Timestamp.from(to), job}; }
    }
    public record Metric(String model, long observationCount, Long inputTokens, Long outputTokens, Long totalTokens,
            BigDecimal totalCost, BigDecimal p50LatencyMs, BigDecimal p95LatencyMs,
            long inputKnown, long outputKnown, long totalKnown) { }
    public record Metrics(String status, String errorCode, Instant from, Instant to, String environment, String source,
            List<Metric> rows, boolean truncated) { }
    public record Point(Instant bucketStart, Long inputTokens, Long outputTokens, Long totalTokens,
            long observationCount, long inputKnown, long outputKnown, long totalKnown) { }
    public record Tokens(String status, String errorCode, Instant from, Instant to, String environment, String source,
            String granularity, List<Point> points) { }
    public record Calls(String status, String errorCode, Instant from, Instant to, String environment, String source,
            List<ObservationRow> observations, String nextCursor, int limit) { }
}
