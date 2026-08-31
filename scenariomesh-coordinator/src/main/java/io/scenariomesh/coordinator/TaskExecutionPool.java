package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ScenarioTask;

import java.util.List;

/**
 * Reusable execution capacity for one logical Maven invocation.
 *
 * <p>A Maven rerun is a new execution round over a subset of the original logical tests,
 * not a new discovery run. Infrastructure retry remains internal to one round.</p>
 */
interface TaskExecutionPool extends AutoCloseable {
    List<ExecutionResult> executeRound(List<ScenarioTask> tasks) throws InterruptedException;

    /** Gracefully drains/stops workers after the last logical Maven rerun round. */
    void finish();
}
