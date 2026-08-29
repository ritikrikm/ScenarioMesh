package io.scenariomesh.coordinator.distributed;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DistributedWorkAuthorityTest {
    @Test
    void resultConsumesExactlyTheLeaseThatIssuedTheRun() {
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        DistributedWorkAuthority authority = new DistributedWorkAuthority(new LeaseRegistry(Duration.ofSeconds(30)));
        ScenarioTask task = task("one");

        Envelope run = authority.issueRun("unit-1", "worker-1", 1, List.of(task), now);
        ExecutionResult result = passed(task, "worker-1", 1, now.plusMillis(1), now.plusMillis(5));
        Envelope response = Envelope.resultBatch("worker-1", run.workUnitId(), run.leaseId(),
                List.of(), List.of(result), null);

        WorkLease accepted = authority.acceptResult("worker-1", response, now.plusSeconds(1));
        assertEquals(run.leaseId(), accepted.leaseId());
        assertThrows(LeaseRegistry.StaleLeaseException.class,
                () -> authority.acceptResult("worker-1", response, now.plusSeconds(2)));
    }

    @Test
    void negotiatedBridgeResultIsValidatedByLeaseIdentityNotCoordinatorLatestVersion() {
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        DistributedWorkAuthority authority = new DistributedWorkAuthority(new LeaseRegistry(Duration.ofSeconds(30)));
        ScenarioTask task = task("bridge");
        Envelope run = authority.issueRun("unit-bridge", "worker-v8", 1, List.of(task), now);
        Envelope bridgeResponse = Envelope.resultBatch("worker-v8", run.workUnitId(), run.leaseId(), List.of(),
                List.of(passed(task, "worker-v8", 1, now, now.plusMillis(5))), null)
                .withProtocolVersion(Protocol.BOOTSTRAP_VERSION);

        WorkLease accepted = authority.acceptResult("worker-v8", bridgeResponse, now.plusSeconds(1));
        assertEquals(run.leaseId(), accepted.leaseId());
    }

    @Test
    void replacedLeaseRejectsLateResultFromPreviousWorker() {
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        LeaseRegistry registry = new LeaseRegistry(Duration.ofSeconds(30));
        DistributedWorkAuthority authority = new DistributedWorkAuthority(registry);
        ScenarioTask task = task("one");

        Envelope oldRun = authority.issueRun("unit-1", "worker-1", 1, List.of(task), now);
        Envelope newRun = authority.issueRun("unit-1", "worker-2", 2, List.of(task), now.plusSeconds(2));

        Envelope late = Envelope.resultBatch("worker-1", oldRun.workUnitId(), oldRun.leaseId(),
                List.of(), List.of(passed(task, "worker-1", 1, now, now.plusSeconds(1))), null);
        assertThrows(LeaseRegistry.StaleLeaseException.class,
                () -> authority.acceptResult("worker-1", late, now.plusSeconds(3)));

        Envelope current = Envelope.resultBatch("worker-2", newRun.workUnitId(), newRun.leaseId(),
                List.of(), List.of(passed(task, "worker-2", 2, now.plusSeconds(2), now.plusSeconds(3))), null);
        assertEquals(newRun.leaseId(), authority.acceptResult("worker-2", current, now.plusSeconds(4)).leaseId());
    }

    @Test
    void workerLossCanBeReassignedImmediatelyAndLateResultCannotWin() {
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        DistributedWorkAuthority authority = new DistributedWorkAuthority(new LeaseRegistry(Duration.ofMinutes(2)));
        ScenarioTask task = task("recover");

        Envelope oldRun = authority.issueRun("unit-recover", "agent-a-worker-1", 1, List.of(task), now);
        assertEquals(List.of("unit-recover"), authority.revokeWorker("agent-a-worker-1"));

        Envelope replacement = authority.issueRun("unit-recover", "agent-b-worker-4", 2,
                List.of(task), now.plusSeconds(1));
        Envelope late = Envelope.resultBatch("agent-a-worker-1", oldRun.workUnitId(), oldRun.leaseId(),
                List.of(), List.of(passed(task, "agent-a-worker-1", 1, now, now.plusSeconds(2))), null);
        assertThrows(LeaseRegistry.StaleLeaseException.class,
                () -> authority.acceptResult("agent-a-worker-1", late, now.plusSeconds(3)));

        Envelope current = Envelope.resultBatch("agent-b-worker-4", replacement.workUnitId(), replacement.leaseId(),
                List.of(), List.of(passed(task, "agent-b-worker-4", 2, now.plusSeconds(1), now.plusSeconds(3))), null);
        assertEquals(replacement.leaseId(),
                authority.acceptResult("agent-b-worker-4", current, now.plusSeconds(4)).leaseId());
    }

    @Test
    void heartbeatRenewsOnlyCurrentWorkerLease() {
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        DistributedWorkAuthority authority = new DistributedWorkAuthority(new LeaseRegistry(Duration.ofSeconds(10)));
        ScenarioTask task = task("one");
        Envelope run = authority.issueRun("unit-1", "worker-1", 1, List.of(task), now);

        WorkLease renewed = authority.heartbeat("worker-1",
                authority.heartbeatMessage("worker-1", run.workUnitId(), run.leaseId(), null),
                now.plusSeconds(5));
        assertEquals(now.plusSeconds(15), renewed.expiresAt());

        assertThrows(LeaseRegistry.StaleLeaseException.class,
                () -> authority.heartbeat("worker-2",
                        authority.heartbeatMessage("worker-2", run.workUnitId(), run.leaseId(), null),
                        now.plusSeconds(6)));
    }

    private ScenarioTask task(String id) {
        return new ScenarioTask(new ScenarioId(id), id, "junit-platform", "junit5",
                null, null, id, Set.of(), Map.of());
    }

    private ExecutionResult passed(ScenarioTask task, String workerId, int attempt,
                                   Instant started, Instant finished) {
        return new ExecutionResult(task.id(), task.displayName(), ResultStatus.PASSED,
                Duration.between(started, finished), new WorkerId(workerId), attempt,
                started, finished, null, null);
    }
}
