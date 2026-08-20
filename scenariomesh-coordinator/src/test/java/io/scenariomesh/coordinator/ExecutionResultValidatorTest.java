package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
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

        assertProtocolFailure(validated, "scenario id");
    }

    @Test
    void envelopeWorkerAndAttemptMustMatchDispatch() {
        Instant dispatched = Instant.now();
        ExecutionResult result = result(task, "worker-1", 1, dispatched, dispatched.plusMillis(10));
        Envelope wrongEnvelope = new Envelope(
                Protocol.VERSION, Protocol.Type.RESULT, "worker-2", null, null,
                9, result, null, null);

        ExecutionResult validated = validator.validateOrFailure(
                task, "worker-1", 1, dispatched, wrongEnvelope);

        assertProtocolFailure(validated, "envelope worker id");
        assertTrue(validated.failureMessage().contains("envelope attempt"));
    }

    @Test
    void wrongProtocolVersionIsNeverAccepted() {
        Instant dispatched = Instant.now();
        ExecutionResult result = result(task, "worker-1", 1, dispatched, dispatched.plusMillis(10));
        Envelope wrongVersion = new Envelope(
                Protocol.VERSION + 1, Protocol.Type.RESULT, "worker-1", null, null,
                1, result, null, null);

        ExecutionResult validated = validator.validateOrFailure(
                task, "worker-1", 1, dispatched, wrongVersion);

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

    private void assertProtocolFailure(ExecutionResult result, String expectedDetail) {
        assertEquals(ResultStatus.INFRASTRUCTURE_FAILURE, result.status());
        assertEquals(ExecutionResultValidator.FAILURE_TYPE, result.failureType());
        assertTrue(result.failureMessage().contains(expectedDetail), result.failureMessage());
    }

    private ScenarioTask task(String id, String selector) {
        return new ScenarioTask(
                new ScenarioId(id), id, "junit-platform", "junit5",
                null, null, selector, Set.of(), Map.of());
    }

    private ExecutionResult result(
            ScenarioTask forTask,
            String workerId,
            int attempt,
            Instant started,
            Instant finished) {
        return new ExecutionResult(
                forTask.id(), forTask.displayName(), ResultStatus.PASSED,
                Duration.between(started, finished), new WorkerId(workerId), attempt,
                started, finished, null, null);
    }
}
