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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates that a worker response is the exact terminal result set for one dispatched work unit. */
final class ExecutionResultValidator {
    static final String FAILURE_TYPE = "ProtocolResultValidationFailure";

    ExecutionResult validateOrFailure(
            ScenarioTask task,
            String expectedWorkerId,
            int expectedAttempt,
            Instant dispatchStartedAt,
            Envelope envelope) {
        return validateBatchOrFailures(
                List.of(task), expectedWorkerId, expectedAttempt, dispatchStartedAt, envelope).get(0);
    }

    List<ExecutionResult> validateBatchOrFailures(
            List<ScenarioTask> tasks,
            String expectedWorkerId,
            int expectedAttempt,
            Instant dispatchStartedAt,
            Envelope envelope) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("Result validation requires at least one dispatched task");
        }
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
        if (envelope.error() != null && !envelope.error().isBlank()) {
            violations.add("worker response error='" + envelope.error() + "'");
        }

        Map<String, ScenarioTask> expectedById = new HashMap<>();
        for (ScenarioTask task : tasks) expectedById.put(task.id().value(), task);
        Map<String, ExecutionResult> actualById = new HashMap<>();
        Set<String> duplicateIds = new HashSet<>();
        for (ExecutionResult result : envelope.results()) {
            String id = result.scenarioId().value();
            if (actualById.putIfAbsent(id, result) != null) duplicateIds.add(id);
        }
        if (!duplicateIds.isEmpty()) violations.add("duplicate result ids=" + duplicateIds);
        Set<String> missing = new HashSet<>(expectedById.keySet());
        missing.removeAll(actualById.keySet());
        if (!missing.isEmpty()) violations.add("missing result ids=" + missing);
        Set<String> unexpected = new HashSet<>(actualById.keySet());
        unexpected.removeAll(expectedById.keySet());
        if (!unexpected.isEmpty()) violations.add("unexpected result ids=" + unexpected);
        if (envelope.results().size() != tasks.size()) {
            violations.add("result count expected=" + tasks.size() + " actual=" + envelope.results().size());
        }

        if (violations.isEmpty()) {
            for (ScenarioTask task : tasks) {
                validateResult(task, expectedWorkerId, expectedAttempt, dispatchStartedAt,
                        actualById.get(task.id().value()), violations);
            }
        }
        if (!violations.isEmpty()) {
            return failures(tasks, expectedWorkerId, expectedAttempt, dispatchStartedAt, violations);
        }
        List<ExecutionResult> ordered = new ArrayList<>(tasks.size());
        for (ScenarioTask task : tasks) ordered.add(actualById.get(task.id().value()));
        return List.copyOf(ordered);
    }

    private void validateResult(
            ScenarioTask task,
            String expectedWorkerId,
            int expectedAttempt,
            Instant dispatchStartedAt,
            ExecutionResult result,
            List<String> violations) {
        if (result == null) {
            violations.add("missing result for task '" + task.id().value() + "'");
            return;
        }
        if (!task.id().equals(result.scenarioId())) {
            violations.add("scenario id expected='" + task.id().value()
                    + "' actual='" + result.scenarioId().value() + "'");
        }
        if (!task.displayName().equals(result.displayName())) {
            violations.add("display name does not match dispatched task '" + task.id().value() + "'");
        }
        if (!expectedWorkerId.equals(result.workerId().value())) {
            violations.add("result worker id expected='" + expectedWorkerId
                    + "' actual='" + result.workerId().value() + "'");
        }
        if (expectedAttempt != result.attempt()) {
            violations.add("result attempt expected=" + expectedAttempt + " actual=" + result.attempt());
        }
        if (result.attempt() < 1) violations.add("result attempt must be positive");
        if (result.duration().isNegative()) violations.add("duration must not be negative");
        if (result.finishedAt().isBefore(result.startedAt())) violations.add("finishedAt precedes startedAt");
        if (result.startedAt().isBefore(dispatchStartedAt.minusSeconds(1))) {
            violations.add("result started before its dispatch window");
        }
    }

    private List<ExecutionResult> failures(
            List<ScenarioTask> tasks,
            String expectedWorkerId,
            int expectedAttempt,
            Instant dispatchStartedAt,
            List<String> violations) {
        Instant finished = Instant.now();
        String message = "Worker returned a response that violated the dispatch contract: "
                + String.join("; ", violations);
        return tasks.stream().map(task -> new ExecutionResult(
                task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                Duration.between(dispatchStartedAt, finished), new WorkerId(expectedWorkerId),
                expectedAttempt, dispatchStartedAt, finished, message, FAILURE_TYPE)).toList();
    }
}
