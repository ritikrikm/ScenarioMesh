package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExecutionHistoryStoreTest {
    @TempDir
    Path directory;

    @Test
    void persistsAndReusesStableLeafDurationWithoutTreatingInfrastructureFailureAsHistory() {
        ExecutionHistoryStore store = new ExecutionHistoryStore();
        ScenarioTask fast = task("fast", Map.of());
        ScenarioTask infra = task("infra", Map.of());
        Instant start = Instant.parse("2026-08-27T00:00:00Z");

        store.update(directory, List.of(fast, infra), List.of(
                result(fast, ResultStatus.PASSED, 1200, start),
                result(infra, ResultStatus.INFRASTRUCTURE_FAILURE, 9000, start)));

        List<ScenarioTask> enriched = store.enrich(directory, List.of(fast, infra));
        assertEquals("1200", enriched.get(0).metadata().get(ExecutionHistoryStore.ESTIMATED_DURATION_MILLIS));
        assertFalse(enriched.get(1).metadata().containsKey(ExecutionHistoryStore.ESTIMATED_DURATION_MILLIS));
    }

    @Test
    void persistsScopeEstimateAsSumOfTerminalLeafDurations() {
        ExecutionHistoryStore store = new ExecutionHistoryStore();
        ScenarioTask first = task("one", Map.of("executionScopeId", "class:PaymentTest"));
        ScenarioTask second = task("two", Map.of("executionScopeId", "class:PaymentTest"));
        Instant start = Instant.parse("2026-08-27T00:00:00Z");

        store.update(directory, List.of(first, second), List.of(
                result(first, ResultStatus.PASSED, 700, start),
                result(second, ResultStatus.TEST_FAILURE, 1300, start)));

        List<ScenarioTask> enriched = store.enrich(directory, List.of(first, second));
        assertEquals("2000", enriched.get(0).metadata().get(ExecutionHistoryStore.ESTIMATED_DURATION_MILLIS));
        assertEquals("2000", enriched.get(1).metadata().get(ExecutionHistoryStore.ESTIMATED_DURATION_MILLIS));
    }

    private ScenarioTask task(String id, Map<String, String> metadata) {
        return new ScenarioTask(new ScenarioId(id), id, "junit-platform", "junit5",
                null, null, id, Set.of(), metadata);
    }

    private ExecutionResult result(ScenarioTask task, ResultStatus status, long millis, Instant start) {
        return new ExecutionResult(task.id(), task.displayName(), status, Duration.ofMillis(millis),
                new WorkerId("worker-1"), 1, start, start.plusMillis(millis), null, null);
    }
}
