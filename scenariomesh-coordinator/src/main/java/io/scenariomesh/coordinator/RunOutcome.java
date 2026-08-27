package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.RunId;
import io.scenariomesh.core.Domain.ScenarioTask;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public record RunOutcome(
        RunId runId,
        List<String> adapters,
        List<ScenarioTask> tasks,
        List<ExecutionResult> results,
        Duration duration,
        Path runDirectory) {
    public RunOutcome {
        adapters = List.copyOf(adapters);
        tasks = List.copyOf(tasks);
        results = List.copyOf(results);
    }

    public boolean successful() {
        return !results.isEmpty() && results.stream().allMatch(ExecutionResult::buildSuccessful);
    }
}
