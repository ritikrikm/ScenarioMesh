package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionHistoryHardeningTest {
    @TempDir Path directory;

    @Test
    void corruptHistoryIsQuarantinedAndDoesNotAffectExecution() throws Exception {
        Files.writeString(directory.resolve("execution-history.json"), "{ definitely-not-json");
        ExecutionHistoryStore store = new ExecutionHistoryStore();
        ScenarioTask task = task("safe");
        List<ScenarioTask> enriched = store.enrich(directory, List.of(task));
        assertFalse(enriched.get(0).metadata().containsKey(ExecutionHistoryStore.ESTIMATED_DURATION_MILLIS));
        assertTrue(Files.isRegularFile(directory.resolve("execution-history.corrupt.json")));
    }

    @Test
    void unsupportedFutureHistoryVersionFallsBackDeterministically() throws Exception {
        Files.writeString(directory.resolve("execution-history.json"), """
                {"version":999,"entries":{"x":{"estimateMillis":9999,"lastObservedEpochMillis":1}}}
                """);
        ScenarioTask task = task("x");
        List<ScenarioTask> enriched = new ExecutionHistoryStore().enrich(directory, List.of(task));
        assertFalse(enriched.get(0).metadata().containsKey(ExecutionHistoryStore.ESTIMATED_DURATION_MILLIS));
    }

    @Test
    void writesVersionedHistoryAndNeverLearnsInfrastructureDuration() throws Exception {
        ExecutionHistoryStore store = new ExecutionHistoryStore();
        ScenarioTask passed = task("passed");
        ScenarioTask infra = task("infra");
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        store.update(directory, List.of(passed, infra), List.of(
                result(passed, ResultStatus.PASSED, 1000, now),
                result(infra, ResultStatus.INFRASTRUCTURE_FAILURE, 9999, now)));
        String json = Files.readString(directory.resolve("execution-history.json"));
        assertTrue(json.contains("\"version\" : 2"));
        assertTrue(json.contains("passed"));
        assertFalse(json.contains("infra"));
    }

    private ScenarioTask task(String id) {
        return new ScenarioTask(new ScenarioId(id), id, "junit-platform", "junit5",
                null, null, id, Set.of(), Map.of());
    }

    private ExecutionResult result(ScenarioTask task, ResultStatus status, long millis, Instant start) {
        return new ExecutionResult(task.id(), task.displayName(), status, Duration.ofMillis(millis),
                new WorkerId("worker"), 1, start, start.plusMillis(millis), null, null);
    }
}
