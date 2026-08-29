package io.scenariomesh.core;

import io.scenariomesh.core.Domain.WorkerStatus;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Authoritative worker lifecycle state machine.
 *
 * <p>The transition graph belongs in core because worker lifecycle is a domain
 * invariant shared by local and remote execution. Transport/coordinator code may
 * request transitions, but must not define a competing lifecycle.</p>
 */
public final class WorkerStateMachine {
    private static final Map<WorkerStatus, Set<WorkerStatus>> ALLOWED = Map.of(
            WorkerStatus.STARTING, Set.of(WorkerStatus.READY, WorkerStatus.UNHEALTHY, WorkerStatus.DEAD),
            WorkerStatus.READY, Set.of(WorkerStatus.BUSY, WorkerStatus.IDLE, WorkerStatus.DRAINING,
                    WorkerStatus.STOPPING, WorkerStatus.UNHEALTHY),
            WorkerStatus.IDLE, Set.of(WorkerStatus.BUSY, WorkerStatus.DRAINING,
                    WorkerStatus.STOPPING, WorkerStatus.UNHEALTHY),
            WorkerStatus.BUSY, Set.of(WorkerStatus.READY, WorkerStatus.DRAINING,
                    WorkerStatus.UNHEALTHY, WorkerStatus.DEAD),
            WorkerStatus.DRAINING, Set.of(WorkerStatus.STOPPING, WorkerStatus.DEAD),
            WorkerStatus.STOPPING, Set.of(WorkerStatus.STOPPED, WorkerStatus.DEAD),
            WorkerStatus.UNHEALTHY, Set.of(WorkerStatus.DRAINING, WorkerStatus.DEAD),
            WorkerStatus.STOPPED, Set.of(),
            WorkerStatus.DEAD, Set.of());

    private WorkerStatus state = WorkerStatus.STARTING;

    public synchronized WorkerStatus state() {
        return state;
    }

    public synchronized boolean canTransitionTo(WorkerStatus next) {
        Objects.requireNonNull(next, "next");
        return ALLOWED.get(state).contains(next);
    }

    public synchronized void transition(WorkerStatus next) {
        Objects.requireNonNull(next, "next");
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("Invalid worker transition " + state + " -> " + next);
        }
        state = next;
    }
}
