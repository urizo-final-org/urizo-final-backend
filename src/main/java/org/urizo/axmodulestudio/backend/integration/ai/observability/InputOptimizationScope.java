package org.urizo.axmodulestudio.backend.integration.ai.observability;

import java.util.List;
import java.util.UUID;

/** Local, body-free diagnostics. Never sent to the provider or remote telemetry. */
public final class InputOptimizationScope implements AutoCloseable {
    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();
    private final Snapshot previous;

    public InputOptimizationScope(boolean rtkEnabled, boolean retentionEnabled, List<Decision> decisions) {
        previous = CURRENT.get();
        CURRENT.set(new Snapshot(UUID.randomUUID(), rtkEnabled, retentionEnabled, List.copyOf(decisions)));
    }

    public static Snapshot current() { return CURRENT.get(); }
    public static void finalBudget(List<Decision> decisions) {
        var snapshot = current();
        if (snapshot == null) return;
        var all = new java.util.ArrayList<>(snapshot.decisions().stream()
                .filter(d -> !"REQUEST_BUDGET".equals(d.operation())).toList());
        all.addAll(decisions);
        CURRENT.set(new Snapshot(snapshot.processingId(), snapshot.rtkEnabled(), snapshot.retentionEnabled(), List.copyOf(all)));
    }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }

    public record Snapshot(UUID processingId, boolean rtkEnabled, boolean retentionEnabled, List<Decision> decisions) { }
    public record Decision(String toolCallId, String tool, String operation, String reason,
            int beforeBytes, int afterBytes, boolean firstProcessing) { }
}
