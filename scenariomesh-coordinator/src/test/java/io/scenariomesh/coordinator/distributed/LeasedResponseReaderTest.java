package io.scenariomesh.coordinator.distributed;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.protocol.Protocol.Envelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeasedResponseReaderTest {
    private final Instant start = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void consumesAuthoritativeHeartbeatsBeforeReturningTerminalResult() throws Exception {
        LeaseRegistry leases = new LeaseRegistry(Duration.ofSeconds(30));
        DistributedWorkAuthority authority = new DistributedWorkAuthority(leases);
        ScenarioTask task = task();
        Envelope run = authority.issueRun("unit-1", "worker-1", 1, List.of(task), start);
        Envelope heartbeat = Envelope.heartbeat("worker-1", "unit-1", run.leaseId(), null);
        ExecutionResult result = result(task);
        Envelope terminal = Envelope.resultBatch(
                "worker-1", "unit-1", run.leaseId(), List.of(), List.of(result), null);
        ArrayDeque<Envelope> responses = new ArrayDeque<>(List.of(heartbeat, terminal));
        AtomicReference<Instant> observedHeartbeat = new AtomicReference<>();

        LeasedResponseReader reader = new LeasedResponseReader(authority, () -> start.plusSeconds(5));
        Envelope returned = reader.readTerminal("worker-1", Duration.ofSeconds(2),
                ignored -> responses.poll(), observedHeartbeat::set);

        assertSame(terminal, returned);
        assertEquals(start.plusSeconds(5), observedHeartbeat.get());
        authority.acceptResult("worker-1", returned, start.plusSeconds(6));
        assertEquals(0, leases.activeLeaseCount());
    }

    @Test
    void authoritativeLeaseHeartbeatKeepsWorkerDirectoryLiveDuringLongWork() throws Exception {
        LeaseRegistry leases = new LeaseRegistry(Duration.ofMinutes(1));
        DistributedWorkAuthority authority = new DistributedWorkAuthority(leases);
        RemoteWorkerDirectory directory = new RemoteWorkerDirectory(Duration.ofSeconds(20));
        directory.register(new RemoteWorkerRegistration(
                "worker-1", "fp", 1, 21, "Linux", "amd64",
                Set.of("junit-platform"), Set.of("junit-jupiter"), Map.of()), start);
        ScenarioTask task = task();
        Envelope run = authority.issueRun("unit-1", "worker-1", 1, List.of(task), start);
        Envelope heartbeat = Envelope.heartbeat("worker-1", "unit-1", run.leaseId(), null);
        Envelope terminal = Envelope.resultBatch(
                "worker-1", "unit-1", run.leaseId(), List.of(), List.of(result(task)), null);
        ArrayDeque<Envelope> responses = new ArrayDeque<>(List.of(heartbeat, terminal));

        LeasedResponseReader reader = new LeasedResponseReader(authority, () -> start.plusSeconds(25));
        Envelope returned = reader.readTerminal("worker-1", Duration.ofSeconds(2),
                ignored -> responses.poll(), heartbeatAt -> directory.heartbeat("worker-1", heartbeatAt));

        assertSame(terminal, returned);
        assertEquals(1, directory.eligible(
                "fp", "junit-platform", "junit-jupiter", start.plusSeconds(26)).size());
    }

    @Test
    void rejectsHeartbeatFromWrongWorkerBeforeNotifyingLivenessObserver() {
        LeaseRegistry leases = new LeaseRegistry(Duration.ofSeconds(30));
        DistributedWorkAuthority authority = new DistributedWorkAuthority(leases);
        ScenarioTask task = task();
        Envelope run = authority.issueRun("unit-1", "worker-1", 1, List.of(task), start);
        Envelope badHeartbeat = Envelope.heartbeat("worker-2", "unit-1", run.leaseId(), null);
        AtomicReference<Instant> observedHeartbeat = new AtomicReference<>();

        LeasedResponseReader reader = new LeasedResponseReader(authority, () -> start.plusSeconds(5));
        assertThrows(LeaseRegistry.StaleLeaseException.class,
                () -> reader.readTerminal("worker-1", Duration.ofSeconds(2),
                        ignored -> badHeartbeat, observedHeartbeat::set));
        assertNull(observedHeartbeat.get());
    }

    @Test
    void heartbeatDoesNotExtendHardTaskTimeout() {
        LeaseRegistry leases = new LeaseRegistry(Duration.ofSeconds(30));
        DistributedWorkAuthority authority = new DistributedWorkAuthority(leases);
        LeasedResponseReader reader = new LeasedResponseReader(authority, () -> start);

        assertThrows(IllegalArgumentException.class,
                () -> reader.readTerminal("worker-1", Duration.ZERO, ignored -> null));
    }

    private ScenarioTask task() {
        return new ScenarioTask(new ScenarioId("task-1"), "task", "junit-platform", "junit5",
                null, null, "selector", Set.of(), Map.of());
    }

    private ExecutionResult result(ScenarioTask task) {
        return new ExecutionResult(task.id(), task.displayName(), ResultStatus.PASSED,
                Duration.ofMillis(10), new WorkerId("worker-1"), 1,
                start.plusSeconds(1), start.plusSeconds(1).plusMillis(10), null, null);
    }
}
