package io.scenariomesh.coordinator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.workerruntime.JsonCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistent duration history keyed by stable task and lifecycle-scope identity. */
final class ExecutionHistoryStore {
    static final String ESTIMATED_DURATION_MILLIS = "estimatedDurationMillis";
    private static final String EXECUTION_SCOPE_ID = "executionScopeId";
    private static final String FILE_NAME = "execution-history.json";
    private static final String SCOPE_PREFIX = "scope:";
    private final ObjectMapper mapper = JsonCodec.create();

    List<ScenarioTask> enrich(Path reportingDirectory, List<ScenarioTask> tasks) {
        Map<String, Long> history = load(reportingDirectory);
        if (history.isEmpty()) return tasks;
        List<ScenarioTask> enriched = new ArrayList<>(tasks.size());
        for (ScenarioTask task : tasks) {
            String scope = task.metadata().get(EXECUTION_SCOPE_ID);
            Long estimate = scope == null || scope.isBlank()
                    ? history.get(task.id().value())
                    : history.get(SCOPE_PREFIX + scope);
            if (estimate == null || estimate <= 0) estimate = history.get(task.id().value());
            if (estimate == null || estimate <= 0) {
                enriched.add(task);
                continue;
            }
            Map<String, String> metadata = new LinkedHashMap<>(task.metadata());
            metadata.put(ESTIMATED_DURATION_MILLIS, Long.toString(estimate));
            enriched.add(new ScenarioTask(task.id(), task.displayName(), task.adapterId(), task.framework(),
                    task.source(), task.line(), task.selector(), task.tags(), metadata));
        }
        return List.copyOf(enriched);
    }

    void update(Path reportingDirectory, List<ScenarioTask> tasks, List<ExecutionResult> results) {
        try {
            Files.createDirectories(reportingDirectory);
            Map<String, Long> history = new LinkedHashMap<>(load(reportingDirectory));
            Map<String, ScenarioTask> taskById = new HashMap<>();
            for (ScenarioTask task : tasks) taskById.put(task.id().value(), task);
            Map<String, Long> observedScopeMillis = new LinkedHashMap<>();

            for (ExecutionResult result : results) {
                if (!isExecutionSignal(result.status())) continue;
                long observed = Math.max(1L, result.duration().toMillis());
                mergeEstimate(history, result.scenarioId().value(), observed);
                ScenarioTask task = taskById.get(result.scenarioId().value());
                if (task != null) {
                    String scope = task.metadata().get(EXECUTION_SCOPE_ID);
                    if (scope != null && !scope.isBlank()) observedScopeMillis.merge(scope, observed, Long::sum);
                }
            }
            observedScopeMillis.forEach((scope, observed) -> mergeEstimate(history, SCOPE_PREFIX + scope, observed));

            Path target = reportingDirectory.resolve(FILE_NAME);
            Path temporary = reportingDirectory.resolve(FILE_NAME + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), history);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception unsupportedAtomicMove) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // Scheduling history is an optimization only and must never change test outcomes.
        }
    }

    private void mergeEstimate(Map<String, Long> history, String key, long observed) {
        Long previous = history.get(key);
        long estimate = previous == null ? observed
                : Math.max(1L, Math.round(previous * 0.75d + observed * 0.25d));
        history.put(key, estimate);
    }

    private Map<String, Long> load(Path reportingDirectory) {
        try {
            Path path = reportingDirectory.resolve(FILE_NAME);
            if (!Files.isRegularFile(path)) return Map.of();
            Map<String, Long> value = mapper.readValue(path.toFile(), new TypeReference<Map<String, Long>>() {});
            return value == null ? Map.of() : Map.copyOf(value);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private boolean isExecutionSignal(ResultStatus status) {
        return status == ResultStatus.PASSED || status == ResultStatus.SKIPPED || status == ResultStatus.TEST_FAILURE;
    }
}
