package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Validates that a worker result is the terminal result for the exact dispatch it received. */
final class ExecutionResultValidator {
    ExecutionResult validateOrFailure(
            ScenarioTask task,
            String expectedWorkerId,
            int expectedAttempt,
            Instant dispatchStartedAt,
            ExecutionResult result) {
        List<String> violations = new ArrayList<>();

        if (!task.id().equals(result.scenarioId())) {
            violations.add("scenario id expected='" + task.id().value()
                    + "' actual='" + result.scenarioId().value() + "'");
        }
        if (!task.displayName().equals(result.displayName())) {
            violations.add("display name does not match dispatched task");
        }
        if (!expectedWorkerId.equals(result.workerId().value())) {
            violations.add("worker id expected='" + expectedWorkerId
                    + "' actual='" + result.workerId().value() + "'");
        }
        if (expectedAttempt != result.attempt()) {
            violations.add("attempt expected=" + expectedAttempt + " actual=" + result.attempt());
        }
        if (result.attempt() < 1) {
            violations.add("attempt must be positive");
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

        if (violations.isEmpty()) {
            return result;
        }

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
                "Worker returned a result that violated the dispatch contract: "
                        + String.join("; ", violations),
                "ProtocolResultValidationFailure");
    }
}
