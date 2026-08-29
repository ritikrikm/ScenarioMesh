package io.scenariomesh.core;

import io.scenariomesh.core.Domain.WorkerStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkerStateMachineTest {
    @Test
    void followsAuthoritativeHappyPath() {
        WorkerStateMachine machine = new WorkerStateMachine();
        assertEquals(WorkerStatus.STARTING, machine.state());
        assertTrue(machine.canTransitionTo(WorkerStatus.READY));

        machine.transition(WorkerStatus.READY);
        machine.transition(WorkerStatus.BUSY);
        machine.transition(WorkerStatus.READY);
        machine.transition(WorkerStatus.DRAINING);
        machine.transition(WorkerStatus.STOPPING);
        machine.transition(WorkerStatus.STOPPED);

        assertEquals(WorkerStatus.STOPPED, machine.state());
        assertFalse(machine.canTransitionTo(WorkerStatus.READY));
    }

    @Test
    void terminalStatesCannotBeResurrected() {
        WorkerStateMachine stopped = new WorkerStateMachine();
        stopped.transition(WorkerStatus.READY);
        stopped.transition(WorkerStatus.STOPPING);
        stopped.transition(WorkerStatus.STOPPED);
        assertThrows(IllegalStateException.class, () -> stopped.transition(WorkerStatus.READY));

        WorkerStateMachine dead = new WorkerStateMachine();
        dead.transition(WorkerStatus.DEAD);
        assertThrows(IllegalStateException.class, () -> dead.transition(WorkerStatus.STARTING));
    }

    @Test
    void rejectsNullTransitionsAtTheDomainBoundary() {
        WorkerStateMachine machine = new WorkerStateMachine();
        assertThrows(NullPointerException.class, () -> machine.canTransitionTo(null));
        assertThrows(NullPointerException.class, () -> machine.transition(null));
    }
}
