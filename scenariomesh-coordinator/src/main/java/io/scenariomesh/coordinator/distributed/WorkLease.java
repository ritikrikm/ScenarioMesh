package io.scenariomesh.coordinator.distributed;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Coordinator authority for one attempt of one atomic ScenarioMesh work unit. */
public record WorkLease(
        String leaseId,
        String workerId,
        int attempt,
        List<String> taskIds,
        Instant issuedAt,
        Instant expiresAt) {

    public WorkLease {
        leaseId = require(leaseId, "leaseId");
        workerId = require(workerId, "workerId");
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        taskIds = List.copyOf(taskIds == null ? List.of() : taskIds);
        if (taskIds.isEmpty()) throw new IllegalArgumentException("work lease requires at least one task id");
        if (taskIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("work lease task ids must not be blank");
        }
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("lease expiry must be after issue time");
    }

    public boolean expiredAt(Instant now) {
        return !Objects.requireNonNull(now, "now").isBefore(expiresAt);
    }

    public WorkLease renewedUntil(Instant newExpiry) {
        Objects.requireNonNull(newExpiry, "newExpiry");
        if (!newExpiry.isAfter(expiresAt)) throw new IllegalArgumentException("renewed lease expiry must move forward");
        return new WorkLease(leaseId, workerId, attempt, taskIds, issuedAt, newExpiry);
    }

    private static String require(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
