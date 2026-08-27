package io.scenariomesh.coordinator;

import java.time.Instant;
import java.util.Map;

/** Stable structured runtime event suitable for JSONL and optional telemetry bridges. */
public record RunEvent(
        Instant timestamp,
        String runId,
        String type,
        String workerId,
        String hostId,
        String taskId,
        String scopeId,
        String workUnitId,
        String leaseId,
        Integer attempt,
        String adapter,
        Integer queueDepth,
        Integer busyWorkers,
        Long durationMillis,
        String message,
        Map<String, String> attributes) {

    public RunEvent {
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }

    /** Source-compatible convenience constructor for existing observability sinks. */
    public RunEvent(Instant timestamp, String runId, String type, String workerId, String taskId, String message) {
        this(timestamp, runId, type, workerId, null, taskId, null, null, null,
                null, null, null, null, null, message, Map.of());
    }
}
