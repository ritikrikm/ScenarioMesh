package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.core.RetrySemantics.LogicalStatus;
import io.scenariomesh.core.RetrySemantics.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MavenRerunExecutorTest {
    private final MavenRerunExecutor executor = new MavenRerunExecutor();

    @Test
    void runsOnlyFailedLogicalTestsAfterInitialRound() throws Exception {
        ScenarioTask pass = task("pass");
        ScenarioTask flaky = task("flaky");
        RecordingPool pool = new RecordingPool(
                round(List.of(pass, flaky), List.of(result(pass, ResultStatus.PASSED, 1), result(flaky, ResultStatus.TEST_FAILURE, 1))),
                round(List.of(flaky), List.of(result(flaky, ResultStatus.PASSED, 1))));

        MavenRerunExecutor.Execution execution = executor.execute(
                pool, List.of(pass, flaky), new RetryPolicy(2, 0), null);

        assertEquals(List.of(List.of("pass", "flaky"), List.of("flaky")), pool.requestedIds());
        assertEquals(2, execution.logicalExecutions().size());
        assertEquals(LogicalStatus.PASSED, logical(execution, "pass").status());
        assertEquals(LogicalStatus.FLAKY, logical(execution, "flaky").status());
        assertEquals(2, logical(execution, "flaky").attempts().size());
    }

    @Test
    void infrastructureFailureDoesNotConsumeOrTriggerMavenRerun() throws Exception {
        ScenarioTask task = task("infra");
        RecordingPool pool = new RecordingPool(round(List.of(task),
                List.of(result(task, ResultStatus.INFRASTRUCTURE_FAILURE, 3))));

        MavenRerunExecutor.Execution execution = executor.execute(
                pool, List.of(task), new RetryPolicy(5, 0), null);

        assertEquals(1, pool.requestedIds().size());
        assertEquals(LogicalStatus.INFRASTRUCTURE_FAILED, execution.logicalExecutions().get(0).status());
        assertEquals(3, execution.logicalExecutions().get(0).attempts().get(0).result().attempt(),
                "worker/lease attempt is independent from Maven rerun index");
    }

    @Test
    void exhaustedRerunsKeepFirstFailureCanonical() throws Exception {
        ScenarioTask task = task("always-fails");
        ExecutionResult first = result(task, ResultStatus.TEST_FAILURE, 1, "first");
        ExecutionResult second = result(task, ResultStatus.TEST_FAILURE, 1, "second");
        ExecutionResult third = result(task, ResultStatus.TEST_FAILURE, 1, "third");
        RecordingPool pool = new RecordingPool(
                round(List.of(task), List.of(first)),
                round(List.of(task), List.of(second)),
                round(List.of(task), List.of(third)));

        MavenRerunExecutor.Execution execution = executor.execute(
                pool, List.of(task), new RetryPolicy(2, 0), null);

        assertEquals(LogicalStatus.FAILED, execution.logicalExecutions().get(0).status());
        assertEquals(first, execution.canonicalResults().get(0));
        assertEquals(3, execution.logicalExecutions().get(0).attempts().size());
    }

    @Test
    void parameterizedMaterializerIsReplacedByConcreteIdentityBeforeRerun() throws Exception {
        ScenarioTask materializer = task("template-placeholder");
        ScenarioTask concrete = task("template-invocation-2");
        RecordingPool pool = new RecordingPool(
                round(List.of(concrete), List.of(result(concrete, ResultStatus.TEST_FAILURE, 1))),
                round(List.of(concrete), List.of(result(concrete, ResultStatus.PASSED, 1))));

        MavenRerunExecutor.Execution execution = executor.execute(
                pool, List.of(materializer), new RetryPolicy(1, 0), null);

        assertEquals(List.of(List.of("template-placeholder"), List.of("template-invocation-2")), pool.requestedIds());
        assertEquals("template-invocation-2", execution.logicalTasks().get(0).id().value());
        assertEquals(LogicalStatus.FLAKY, execution.logicalExecutions().get(0).status());
    }

    @Test
    void refusesRerunThatChangesConcreteLogicalIdentity() {
        ScenarioTask original = task("original");
        ScenarioTask changed = task("changed");
        RecordingPool pool = new RecordingPool(
                round(List.of(original), List.of(result(original, ResultStatus.TEST_FAILURE, 1))),
                round(List.of(changed), List.of(result(changed, ResultStatus.PASSED, 1))));

        assertThrows(IllegalStateException.class, () -> executor.execute(
                pool, List.of(original), new RetryPolicy(1, 0), null));
    }

    private io.scenariomesh.core.RetrySemantics.LogicalExecution logical(
            MavenRerunExecutor.Execution execution, String id) {
        return execution.logicalExecutions().stream()
                .filter(item -> item.logicalTask().value().equals(id)).findFirst().orElseThrow();
    }

    private TaskExecutionPool.RoundExecution round(List<ScenarioTask> tasks, List<ExecutionResult> results) {
        return new TaskExecutionPool.RoundExecution(tasks, results);
    }

    private ScenarioTask task(String id) {
        return new ScenarioTask(new ScenarioId(id), id, "junit-platform", "junit5",
                URI.create("file:///tmp/" + id + ".java"), 1, "[engine:junit-jupiter]/[test:" + id + "]",
                Set.of(), Map.of());
    }

    private ExecutionResult result(ScenarioTask task, ResultStatus status, int infrastructureAttempt) {
        return result(task, status, infrastructureAttempt, status.name());
    }

    private ExecutionResult result(ScenarioTask task, ResultStatus status, int infrastructureAttempt, String message) {
        Instant started = Instant.parse("2026-08-30T00:00:00Z");
        return new ExecutionResult(task.id(), task.displayName(), status, Duration.ofMillis(10),
                new WorkerId("worker-1"), infrastructureAttempt, started, started.plusMillis(10),
                status == ResultStatus.PASSED ? null : message,
                status == ResultStatus.TEST_FAILURE ? "AssertionError" : "InfrastructureFailure");
    }

    private static final class RecordingPool implements TaskExecutionPool {
        private final ArrayDeque<RoundExecution> rounds = new ArrayDeque<>();
        private final List<List<String>> requests = new ArrayList<>();

        private RecordingPool(RoundExecution... rounds) {
            this.rounds.addAll(List.of(rounds));
        }

        @Override
        public RoundExecution executeRound(List<ScenarioTask> tasks) {
            requests.add(tasks.stream().map(task -> task.id().value()).toList());
            if (rounds.isEmpty()) throw new AssertionError("unexpected extra execution round");
            return rounds.removeFirst();
        }

        List<List<String>> requestedIds() { return List.copyOf(requests); }
        @Override public void finish() { }
        @Override public void close() { }
    }
}
