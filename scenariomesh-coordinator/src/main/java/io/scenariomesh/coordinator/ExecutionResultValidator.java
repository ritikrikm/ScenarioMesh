package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Validates that a worker result is the terminal result for the exact dispatch it received. */
final class ExecutionResultValidator {
    static final String FAILURE_TYPE = "ProtocolResultValidationFailure";

    ExecutionResult validateOrFailure(
            ScenarioTask task,
            String expectedWorkerId,
            int expectedAttempt,
            Instant dispatchStartedAt,
            Envelope envelope) {
        List<String> violations = new ArrayList<>();

        if (envelope.protocolVersion() != Protocol.VERSION) {
            violations.add("protocol version expected=" + Protocol.VERSION
                    + " actual=" + envelope.protocolVersion());
        }
        if (envelope.type() != Protocol.Type.RESULT) {
            violations.add("response type expected=RESULT actual=" + envelope.type());
        }
        if (!expectedWorkerId.equals(envelope.workerId())) {
            violations.add("envelope worker id expected='" + expectedWorkerId
                    + "' actual='" + envelope.workerId() + "'");
        }
        if (envelope.attempt() == null || envelope.attempt() != expectedAttempt) {
            violations.add("envelope attempt expected=" + expectedAttempt + " actual=" + envelope.attempt());
        }

        ExecutionResult result = envelope.result();
        if (result == null) {
            violations.add("RESULT envelope does not contain an ExecutionResult");
        } else {
            validateResult(task, expectedWorkerId, expectedAttempt, dispatchStartedAt, result, violations);
        }
        if (envelope.error() != null && !envelope.error().isBlank()) {
            violations.add("worker response error='" + envelope.error() + "'");
        }

        if (violations.isEmpty()) {
            return result;
        }
        return failure(task, expectedWorkerId, expectedAttempt, dispatchStartedAt, violations);
    }

    private void validateResult(
            ScenarioTask task,
            String expectedWorkerId,
            int expectedAttempt,
            Instant dispatchStartedAt,
            ExecutionResult result,
            List<String> violations) {
        if (!task.id().equals(result.scenarioId())) {
            violations.add("scenario id expected='" + task.id().value()
                    + "' actual='" + result.scenarioId().value() + "'");
        }
        if (!task.displayName().equals(result.displayName())) {
            violations.add("display name does not match dispatched task");
        }
        if (!expectedWorkerId.equals(result.workerId().value())) {
            violations.add("result worker id expected='" + expectedWorkerId
                    + "' actual='" + result.workerId().value() + "'");
        }
        if (expectedAttempt != result.attempt()) {
            violations.add("result attempt expected=" + expectedAttempt + " actual=" + result.attempt());
        }
        if (result.attempt() < 1) {
            violations.add("result attempt must be positive");
        }
        if (result.duration().isNegative()) {
            violations.add("duration must not be negative");
        }
        if (result.finishedAt().isBefore(result.startedAt())) {
            violations.add("finishedAt precedes startedAt");
        }
        if (result.startedAt().isBefore(dispatchStartedAt.minusSeconds(1))) {
            violations.add("result started before its dispatch window");
        }
    }

    private ExecutionResult failure(
            ScenarioTask task,
            String expectedWorkerId,
            int expectedAttempt,
            Instant dispatchStartedAt,
            List<String> violations) {
        Instant finished = Instant.now();
        return new ExecutionResult(
                task.id(),
                task.displayName(),
                ResultStatus.INFRASTRUCTURE_FAILURE,
                Duration.between(dispatchStartedAt, finished),
                new WorkerId(expectedWorkerId),
                expectedAttempt,
                dispatchStartedAt,
                finished,
                "Worker returned a response that violated the dispatch contract: "
                        + String.join("; ", violations),
                FAILURE_TYPE);
    }
}
