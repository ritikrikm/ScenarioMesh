package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.RunId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.RetrySemantics;
import io.scenariomesh.core.RetrySemantics.ExecutionAttempt;
import io.scenariomesh.core.RetrySemantics.LogicalExecution;
import io.scenariomesh.core.RetrySemantics.RetryCause;
import io.scenariomesh.core.RetrySemantics.RetryPolicy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public record RunOutcome(
        RunId runId,
        List<String> adapters,
        List<ScenarioTask> tasks,
        List<ExecutionResult> results,
        List<LogicalExecution> logicalExecutions,
        RetryPolicy retryPolicy,
        Duration duration,
        Path runDirectory) {
    public RunOutcome {
        adapters = List.copyOf(adapters);
        tasks = List.copyOf(tasks);
        results = List.copyOf(results);
        logicalExecutions = List.copyOf(logicalExecutions == null ? synthesize(results) : logicalExecutions);
        retryPolicy = retryPolicy == null ? RetryPolicy.none() : retryPolicy;
    }

    /** Compatibility constructor for callers that do not use Maven logical reruns. */
    public RunOutcome(RunId runId, List<String> adapters, List<ScenarioTask> tasks,
                      List<ExecutionResult> results, Duration duration, Path runDirectory) {
        this(runId, adapters, tasks, results, null, RetryPolicy.none(), duration, runDirectory);
    }

    public int flakyCount() {
        return (int) logicalExecutions.stream().filter(LogicalExecution::flaky).count();
    }

    public boolean successful() {
        return !results.isEmpty()
                && results.stream().allMatch(ExecutionResult::buildSuccessful)
                && !retryPolicy.failsBuildForFlakes(flakyCount());
    }

    private static List<LogicalExecution> synthesize(List<ExecutionResult> results) {
        return results.stream().map(result -> RetrySemantics.aggregate(List.of(
                new ExecutionAttempt(result.scenarioId(), 0, RetryCause.INITIAL, result)))).toList();
    }
}
