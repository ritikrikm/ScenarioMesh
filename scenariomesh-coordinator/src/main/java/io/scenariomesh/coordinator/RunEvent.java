package io.scenariomesh.coordinator;

import java.time.Instant;

/** Stable structured runtime event that can be exported to external observability systems. */
public record RunEvent(
        Instant timestamp,
        String runId,
        String type,
        String workerId,
        String taskId,
        String message) {}
