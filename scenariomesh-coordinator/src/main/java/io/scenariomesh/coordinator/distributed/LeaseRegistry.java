package io.scenariomesh.coordinator.distributed;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Coordinator-owned source of truth for distributed work attempts.
 *
 * <p>Exactly one live lease may own a work unit. Heartbeats extend only the currently
 * authoritative lease. Results from an expired, replaced, unknown or wrong-worker lease
 * are rejected so an agent that reconnects late cannot double-complete tests.</p>
 */
public final class LeaseRegistry {
    private final Duration leaseDuration;
    private final Map<String, WorkLease> activeByLeaseId = new LinkedHashMap<>();
    private final Map<String, String> activeLeaseByWorkUnit = new LinkedHashMap<>();

    public LeaseRegistry(Duration leaseDuration) {
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be greater than zero");
        }
    }

    public synchronized WorkLease issue(String workUnitId, String workerId, int attempt,
                                        List<String> taskIds, Instant now) {
        String unit = require(workUnitId, "workUnitId");
        Objects.requireNonNull(now, "now");
        String previousLeaseId = activeLeaseByWorkUnit.remove(unit);
        if (previousLeaseId != null) activeByLeaseId.remove(previousLeaseId);

        WorkLease lease = new WorkLease(UUID.randomUUID().toString(), workerId, attempt, taskIds,
                now, now.plus(leaseDuration));
        activeByLeaseId.put(lease.leaseId(), lease);
        activeLeaseByWorkUnit.put(unit, lease.leaseId());
        return lease;
    }

    public synchronized WorkLease heartbeat(String workUnitId, String leaseId, String workerId, Instant now) {
        WorkLease lease = authoritative(workUnitId, leaseId, workerId, now);
        WorkLease renewed = lease.renewedUntil(now.plus(leaseDuration));
        activeByLeaseId.put(renewed.leaseId(), renewed);
        return renewed;
    }

    /** Validates that a terminal result still owns the authoritative attempt, then consumes the lease. */
    public synchronized WorkLease acceptResult(String workUnitId, String leaseId, String workerId, Instant now) {
        WorkLease lease = authoritative(workUnitId, leaseId, workerId, now);
        activeByLeaseId.remove(lease.leaseId());
        activeLeaseByWorkUnit.remove(require(workUnitId, "workUnitId"));
        return lease;
    }

    /** Expires dead-agent leases and returns work-unit ids that are now safe to reschedule. */
    public synchronized List<String> expire(Instant now) {
        Objects.requireNonNull(now, "now");
        List<String> expiredUnits = new ArrayList<>();
        for (Map.Entry<String, String> entry : new ArrayList<>(activeLeaseByWorkUnit.entrySet())) {
            WorkLease lease = activeByLeaseId.get(entry.getValue());
            if (lease == null || lease.expiredAt(now)) {
                activeLeaseByWorkUnit.remove(entry.getKey());
                if (lease != null) activeByLeaseId.remove(lease.leaseId());
                expiredUnits.add(entry.getKey());
            }
        }
        return List.copyOf(expiredUnits);
    }

    /**
     * Immediately fences every lease owned by a worker whose connection/authority has been lost.
     * Returned work-unit ids are safe for the scheduler to requeue immediately; a late result from
     * the retired worker will be rejected even if the original lease deadline has not elapsed yet.
     */
    public synchronized List<String> revokeWorker(String workerId) {
        String worker = require(workerId, "workerId");
        List<String> revokedUnits = new ArrayList<>();
        for (Map.Entry<String, String> entry : new ArrayList<>(activeLeaseByWorkUnit.entrySet())) {
            WorkLease lease = activeByLeaseId.get(entry.getValue());
            if (lease != null && lease.workerId().equals(worker)) {
                activeLeaseByWorkUnit.remove(entry.getKey());
                activeByLeaseId.remove(lease.leaseId());
                revokedUnits.add(entry.getKey());
            }
        }
        return List.copyOf(revokedUnits);
    }

    public synchronized int activeLeaseCount() {
        return activeByLeaseId.size();
    }

    private WorkLease authoritative(String workUnitId, String leaseId, String workerId, Instant now) {
        String unit = require(workUnitId, "workUnitId");
        String leaseKey = require(leaseId, "leaseId");
        String worker = require(workerId, "workerId");
        Objects.requireNonNull(now, "now");

        String authoritativeLeaseId = activeLeaseByWorkUnit.get(unit);
        if (!leaseKey.equals(authoritativeLeaseId)) {
            throw new StaleLeaseException("lease " + leaseKey + " is not authoritative for work unit " + unit);
        }
        WorkLease lease = activeByLeaseId.get(leaseKey);
        if (lease == null) throw new StaleLeaseException("lease " + leaseKey + " is no longer active");
        if (!lease.workerId().equals(worker)) {
            throw new StaleLeaseException("lease " + leaseKey + " belongs to worker " + lease.workerId()
                    + ", not " + worker);
        }
        if (lease.expiredAt(now)) {
            activeByLeaseId.remove(leaseKey);
            activeLeaseByWorkUnit.remove(unit);
            throw new StaleLeaseException("lease " + leaseKey + " expired at " + lease.expiresAt());
        }
        return lease;
    }

    private static String require(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    public static final class StaleLeaseException extends IllegalStateException {
        public StaleLeaseException(String message) { super(message); }
    }
}
