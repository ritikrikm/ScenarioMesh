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
    RoundExecution executeRound(List<ScenarioTask> tasks) throws InterruptedException;

    /** Gracefully drains/stops workers after the last logical Maven rerun round. */
    void finish();

    record RoundExecution(List<ScenarioTask> tasks, List<ExecutionResult> results) {
        RoundExecution {
            tasks = List.copyOf(tasks == null ? List.of() : tasks);
            results = List.copyOf(results == null ? List.of() : results);
        }
    }
}
