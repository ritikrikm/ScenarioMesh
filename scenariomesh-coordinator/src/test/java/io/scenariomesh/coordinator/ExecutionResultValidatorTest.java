package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.core.ScenarioIds;
import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionResultValidatorTest {
    private final ExecutionResultValidator validator = new ExecutionResultValidator();
    private final ScenarioTask task = task("task-1", "selector-1");

    @Test
    void validResultForExactDispatchIsAccepted() {
        Instant dispatched = Instant.now();
        ExecutionResult result = result(task, "worker-1", 2, dispatched, dispatched.plusMillis(10));
        Envelope envelope = Envelope.result("worker-1", result, null);
        assertSame(result, validator.validateOrFailure(task, "worker-1", 2, dispatched, envelope));
    }

    @Test
    void mismatchedScenarioIdBecomesInfrastructureFailure() {
        Instant dispatched = Instant.now();
        ScenarioTask other = task("other-task", "selector-2");
        ExecutionResult wrong = result(other, "worker-1", 1, dispatched, dispatched.plusMillis(10));
        ExecutionResult validated = validator.validateOrFailure(
                task, "worker-1", 1, dispatched, Envelope.result("worker-1", wrong, null));
        assertProtocolFailure(validated, "missing result ids");
    }

    @Test
    void envelopeWorkerAndAttemptMustMatchDispatch() {
        Instant dispatched = Instant.now();
        ExecutionResult result = result(task, "worker-1", 1, dispatched, dispatched.plusMillis(10));
        Envelope wrongEnvelope = new Envelope(
                Protocol.VERSION, Protocol.Type.RESULT, "worker-2", null,
                null, null, null, null,
                List.of(), List.of(), 9, List.of(result), null, null);
        ExecutionResult validated = validator.validateOrFailure(task, "worker-1", 1, dispatched, wrongEnvelope);
        assertProtocolFailure(validated, "envelope worker id");
        assertTrue(validated.failureMessage().contains("envelope attempt"));
    }

    @Test
    void wrongProtocolVersionIsNeverAccepted() {
        Instant dispatched = Instant.now();
        ExecutionResult result = result(task, "worker-1", 1, dispatched, dispatched.plusMillis(10));
        Envelope wrongVersion = new Envelope(
                Protocol.VERSION + 1, Protocol.Type.RESULT, "worker-1", null,
                null, null, null, null,
                List.of(), List.of(), 1, List.of(result), null, null);
        ExecutionResult validated = validator.validateOrFailure(task, "worker-1", 1, dispatched, wrongVersion);
        assertProtocolFailure(validated, "protocol version");
    }

    @Test
    void impossibleTimingIsNeverAccepted() {
        Instant dispatched = Instant.now();
        Instant resultStart = dispatched.plusMillis(20);
        ExecutionResult impossible = new ExecutionResult(
                task.id(), task.displayName(), ResultStatus.PASSED,
                Duration.ofMillis(-1), new WorkerId("worker-1"), 1,
                resultStart, resultStart.minusMillis(1), null, null);
        ExecutionResult validated = validator.validateOrFailure(
                task, "worker-1", 1, dispatched, Envelope.result("worker-1", impossible, null));
        assertProtocolFailure(validated, "duration must not be negative");
        assertTrue(validated.failureMessage().contains("finishedAt precedes startedAt"));
    }

    @Test
    void batchMustReturnEveryDispatchedLeafExactlyOnceAndNothingElse() {
        Instant dispatched = Instant.now();
        ScenarioTask second = task("task-2", "selector-2");
        ScenarioTask unexpected = task("task-3", "selector-3");
        ExecutionResult firstResult = result(task, "worker-1", 1, dispatched, dispatched.plusMillis(5));
        ExecutionResult unexpectedResult = result(unexpected, "worker-1", 1, dispatched, dispatched.plusMillis(6));
        Envelope envelope = Envelope.resultBatch("worker-1", List.of(firstResult, unexpectedResult), null);
        List<ExecutionResult> validated = validator.validateBatchOrFailures(
                List.of(task, second), "worker-1", 1, dispatched, envelope);
        assertEquals(2, validated.size());
        validated.forEach(result -> assertProtocolFailure(result, "missing result ids"));
        assertTrue(validated.get(0).failureMessage().contains("unexpected result ids"));
    }

    @Test
    void runtimeMaterializedDescendantsAreAcceptedButArbitraryTasksAreRejected() {
        Instant dispatched = Instant.now();
        String parentSelector = "[engine:junit-jupiter]/[class:Example]/[test-template:param(int)]";
        Map<String, String> parentMetadata = new LinkedHashMap<>();
        parentMetadata.put("runtimeMaterializer", "true");
        ScenarioTask parent = new ScenarioTask(
                ScenarioIds.from("junit-platform", parentSelector), "param(int)", "junit-platform", "junit5",
                null, null, parentSelector, Set.of(), parentMetadata);

        String childSelector = parentSelector + "/[test-template-invocation:#1]";
        Map<String, String> childMetadata = new LinkedHashMap<>();
        childMetadata.put("parentMaterializerId", parent.id().value());
        childMetadata.put("parentMaterializerSelector", parentSelector);
        ScenarioTask child = new ScenarioTask(
                ScenarioIds.from("junit-platform", childSelector), "[1] 1", "junit-platform", "junit5",
                null, null, childSelector, Set.of(), childMetadata);
        ExecutionResult childResult = result(child, "worker-1", 1, dispatched, dispatched.plusMillis(5));
        Envelope good = Envelope.resultBatch("worker-1", List.of(child), List.of(childResult), null);
        List<ExecutionResult> accepted = validator.validateBatchOrFailures(
                List.of(parent), "worker-1", 1, dispatched, good);
        assertEquals(1, accepted.size());
        assertSame(childResult, accepted.get(0));

        ScenarioTask rogue = task("rogue", "unrelated");
        ExecutionResult rogueResult = result(rogue, "worker-1", 1, dispatched, dispatched.plusMillis(6));
        Envelope bad = Envelope.resultBatch("worker-1", List.of(rogue), List.of(rogueResult), null);
        ExecutionResult rejected = validator.validateBatchOrFailures(
                List.of(parent), "worker-1", 1, dispatched, bad).get(0);
        assertProtocolFailure(rejected, "materialized task has no dispatched materializer parent");
    }

    private void assertProtocolFailure(ExecutionResult result, String expectedDetail) {
        assertEquals(ResultStatus.INFRASTRUCTURE_FAILURE, result.status());
        assertEquals(ExecutionResultValidator.FAILURE_TYPE, result.failureType());
        assertTrue(result.failureMessage().contains(expectedDetail), result.failureMessage());
    }

    private ScenarioTask task(String id, String selector) {
        return new ScenarioTask(new ScenarioId(id), id, "junit-platform", "junit5",
                null, null, selector, Set.of(), Map.of());
    }

    private ExecutionResult result(ScenarioTask forTask, String workerId, int attempt,
                                   Instant started, Instant finished) {
        return new ExecutionResult(forTask.id(), forTask.displayName(), ResultStatus.PASSED,
                Duration.between(started, finished), new WorkerId(workerId), attempt,
                started, finished, null, null);
    }
}
