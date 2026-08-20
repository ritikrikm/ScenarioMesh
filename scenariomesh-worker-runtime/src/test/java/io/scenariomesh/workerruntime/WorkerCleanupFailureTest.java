package io.scenariomesh.workerruntime;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.WorkerTaskCleanup;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerCleanupFailureTest {
    @Test
    void cleanupFailureIsInfrastructureFailureButKeepsOriginalTestFailureEvidence() {
        ScenarioTask task = new ScenarioTask(
                new ScenarioId("task-1"), "payment test", "adapter", "framework",
                null, null, "selector", Set.of(), Map.of());
        ExecutionContext context = new ExecutionContext(
                getClass().getClassLoader(), new WorkerId("worker-1"), 1, Map.of());
        Instant started = Instant.now().minusMillis(10);
        ExecutionResult original = new ExecutionResult(
                task.id(), task.displayName(), ResultStatus.TEST_FAILURE,
                Duration.ofMillis(10), context.workerId(), context.attempt(),
                started, started.plusMillis(10), "expected 200 but got 500", "AssertionError");
        WorkerTaskCleanup failingCleanup = (ignoredTask, ignoredContext, ignoredResult) -> {
            throw new IllegalStateException("driver quit failed");
        };

        ExecutionResult result = WorkerMain.runCleanupHooks(
                List.of(failingCleanup), task, context, original);

        assertEquals(ResultStatus.INFRASTRUCTURE_FAILURE, result.status());
        assertTrue(result.failureType().startsWith("CleanupFailure:"), result.failureType());
        assertTrue(result.failureMessage().contains("driver quit failed"), result.failureMessage());
        assertTrue(result.failureMessage().contains("status=TEST_FAILURE"), result.failureMessage());
        assertTrue(result.failureMessage().contains("failureType=AssertionError"), result.failureMessage());
        assertTrue(result.failureMessage().contains("expected 200 but got 500"), result.failureMessage());
    }

    @Test
    void successfulCleanupReturnsOriginalResultUnchanged() {
        ScenarioTask task = new ScenarioTask(
                new ScenarioId("task-2"), "healthy test", "adapter", "framework",
                null, null, "selector-2", Set.of(), Map.of());
        ExecutionContext context = new ExecutionContext(
                getClass().getClassLoader(), new WorkerId("worker-1"), 1, Map.of());
        Instant now = Instant.now();
        ExecutionResult original = new ExecutionResult(
                task.id(), task.displayName(), ResultStatus.PASSED,
                Duration.ZERO, context.workerId(), context.attempt(), now, now, null, null);

        ExecutionResult result = WorkerMain.runCleanupHooks(
                List.of((ignoredTask, ignoredContext, ignoredResult) -> { }), task, context, original);

        assertEquals(original, result);
    }
}
