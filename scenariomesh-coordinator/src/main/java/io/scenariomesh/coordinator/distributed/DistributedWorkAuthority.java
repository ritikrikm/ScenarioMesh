package io.scenariomesh.coordinator.distributed;

import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;
import io.scenariomesh.protocol.Protocol.WorkerTelemetry;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Coordinator-side authority for turning work units into leased protocol messages.
 *
 * <p>The lease registry is deliberately consulted before worker result payload validation.
 * This prevents a disconnected/replaced worker from completing a newer attempt with a
 * late response even when that response is otherwise structurally valid.</p>
 */
public final class DistributedWorkAuthority {
    private final LeaseRegistry leases;

    public DistributedWorkAuthority(LeaseRegistry leases) {
        this.leases = Objects.requireNonNull(leases, "leases");
    }

    public Envelope issueRun(String workUnitId, String workerId, int attempt,
                             List<ScenarioTask> tasks, Instant now) {
        Objects.requireNonNull(tasks, "tasks");
        if (tasks.isEmpty()) throw new IllegalArgumentException("work unit requires at least one task");
        WorkLease lease = leases.issue(workUnitId, workerId, attempt,
                tasks.stream().map(task -> task.id().value()).toList(), now);
        return Envelope.runBatch(workerId, workUnitId, lease.leaseId(), lease.expiresAt(), tasks, attempt);
    }

    public WorkLease heartbeat(String workerId, Envelope heartbeat, Instant now) {
        requireEnvelope(heartbeat, Protocol.Type.HEARTBEAT, workerId);
        requireLeaseIdentity(heartbeat);
        return leases.heartbeat(heartbeat.workUnitId(), heartbeat.leaseId(), workerId, now);
    }

    /**
     * Atomically proves that a result still owns the current work attempt and consumes
     * that authority. Callers may then validate the result payload itself.
     */
    public WorkLease acceptResult(String workerId, Envelope result, Instant now) {
        requireEnvelope(result, Protocol.Type.RESULT, workerId);
        requireLeaseIdentity(result);
        WorkLease lease = leases.acceptResult(result.workUnitId(), result.leaseId(), workerId, now);
        if (result.attempt() == null || result.attempt() != lease.attempt()) {
            throw new LeaseRegistry.StaleLeaseException("result attempt " + result.attempt()
                    + " does not match authoritative lease attempt " + lease.attempt());
        }
        return lease;
    }

    public Envelope heartbeatMessage(String workerId, String workUnitId, String leaseId,
                                     WorkerTelemetry telemetry) {
        return Envelope.heartbeat(workerId, workUnitId, leaseId, telemetry);
    }

    private void requireEnvelope(Envelope envelope, Protocol.Type type, String workerId) {
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.protocolVersion() != Protocol.VERSION) {
            throw new LeaseRegistry.StaleLeaseException("protocol version " + envelope.protocolVersion()
                    + " does not match coordinator version " + Protocol.VERSION);
        }
        if (envelope.type() != type) {
            throw new LeaseRegistry.StaleLeaseException("expected " + type + " but received " + envelope.type());
        }
        if (!Objects.equals(workerId, envelope.workerId())) {
            throw new LeaseRegistry.StaleLeaseException("envelope worker " + envelope.workerId()
                    + " does not match connection worker " + workerId);
        }
    }

    private void requireLeaseIdentity(Envelope envelope) {
        if (envelope.workUnitId() == null || envelope.workUnitId().isBlank()
                || envelope.leaseId() == null || envelope.leaseId().isBlank()) {
            throw new LeaseRegistry.StaleLeaseException("distributed envelope is missing work-unit/lease identity");
        }
    }
}
