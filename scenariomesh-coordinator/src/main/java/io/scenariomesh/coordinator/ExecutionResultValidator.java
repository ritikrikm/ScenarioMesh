package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.core.ScenarioIds;
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

/** Validates the exact terminal/materialized result set for one dispatched work unit. */
final class ExecutionResultValidator {
    static final String FAILURE_TYPE = "ProtocolResultValidationFailure";
    private static final String META_RUNTIME_MATERIALIZER = "runtimeMaterializer";
    private static final String META_PARENT_MATERIALIZER_ID = "parentMaterializerId";
    private static final String META_PARENT_MATERIALIZER_SELECTOR = "parentMaterializerSelector";

    record ValidatedWorkUnit(List<ScenarioTask> tasks, List<ExecutionResult> results) {
        ValidatedWorkUnit {
            tasks = List.copyOf(tasks);
            results = List.copyOf(results);
        }
    }

    ExecutionResult validateOrFailure(ScenarioTask task, String expectedWorkerId, int expectedAttempt,
                                      Instant dispatchStartedAt, Envelope envelope) {
        return validateBatchOrFailures(List.of(task), expectedWorkerId, expectedAttempt, dispatchStartedAt, envelope).get(0);
    }

    List<ExecutionResult> validateBatchOrFailures(List<ScenarioTask> tasks, String expectedWorkerId,
                                                   int expectedAttempt, Instant dispatchStartedAt, Envelope envelope) {
        return validateWorkUnit(tasks, expectedWorkerId, expectedAttempt, dispatchStartedAt, envelope).results();
    }

    ValidatedWorkUnit validateWorkUnit(List<ScenarioTask> dispatched, String expectedWorkerId,
                                       int expectedAttempt, Instant dispatchStartedAt, Envelope envelope) {
        if (dispatched == null || dispatched.isEmpty()) {
            throw new IllegalArgumentException("Result validation requires at least one dispatched task");
        }
        List<String> violations = new ArrayList<>();
        // Protocol version is a transport/session invariant. RemoteWorkerSession has already authenticated
        // and locked every inbound envelope to the negotiated version before this validator is invoked.
        // Requiring Protocol.VERSION here would incorrectly reject legitimate downgraded bridge sessions.
        if (envelope.type() != Protocol.Type.RESULT) violations.add("response type expected=RESULT actual=" + envelope.type());
        if (!expectedWorkerId.equals(envelope.workerId())) violations.add("envelope worker id expected='" + expectedWorkerId + "' actual='" + envelope.workerId() + "'");
        if (envelope.attempt() == null || envelope.attempt() != expectedAttempt) violations.add("envelope attempt expected=" + expectedAttempt + " actual=" + envelope.attempt());
        if (envelope.error() != null && !envelope.error().isBlank()) violations.add("worker response error='" + envelope.error() + "'");

        List<ScenarioTask> concrete = envelope.materializedTasks().isEmpty() ? dispatched : envelope.materializedTasks();
        Map<String, ScenarioTask> dispatchedById = new HashMap<>();
        for (ScenarioTask task : dispatched) dispatchedById.put(task.id().value(), task);
        Map<String, ScenarioTask> concreteById = new HashMap<>();
        Set<String> duplicateTaskIds = new HashSet<>();
        for (ScenarioTask task : concrete) {
            if (concreteById.putIfAbsent(task.id().value(), task) != null) duplicateTaskIds.add(task.id().value());
            validateConcreteTask(task, dispatchedById, violations);
        }
        if (!duplicateTaskIds.isEmpty()) violations.add("duplicate materialized task ids=" + duplicateTaskIds);

        for (ScenarioTask original : dispatched) {
            if (isMaterializer(original)) {
                boolean represented = concrete.stream().anyMatch(task -> task.id().equals(original.id())
                        || original.id().value().equals(task.metadata().get(META_PARENT_MATERIALIZER_ID)));
                if (!represented) violations.add("runtime materializer produced no concrete task: " + original.id().value());
            } else if (!concreteById.containsKey(original.id().value())) {
                violations.add("missing non-materialized task=" + original.id().value());
            }
        }

        Map<String, ExecutionResult> actualById = new HashMap<>();
        Set<String> duplicateResultIds = new HashSet<>();
        for (ExecutionResult result : envelope.results()) {
            if (actualById.putIfAbsent(result.scenarioId().value(), result) != null) duplicateResultIds.add(result.scenarioId().value());
        }
        if (!duplicateResultIds.isEmpty()) violations.add("duplicate result ids=" + duplicateResultIds);
        Set<String> expectedIds = new HashSet<>(concreteById.keySet());
        Set<String> actualIds = new HashSet<>(actualById.keySet());
        Set<String> missing = new HashSet<>(expectedIds); missing.removeAll(actualIds);
        Set<String> unexpected = new HashSet<>(actualIds); unexpected.removeAll(expectedIds);
        if (!missing.isEmpty()) violations.add("missing result ids=" + missing);
        if (!unexpected.isEmpty()) violations.add("unexpected result ids=" + unexpected);
        if (envelope.results().size() != concrete.size()) violations.add("result count expected=" + concrete.size() + " actual=" + envelope.results().size());

        if (violations.isEmpty()) {
            for (ScenarioTask task : concrete) validateResult(task, expectedWorkerId, expectedAttempt,
                    dispatchStartedAt, actualById.get(task.id().value()), violations);
        }
        if (!violations.isEmpty()) {
            return new ValidatedWorkUnit(dispatched,
                    failures(dispatched, expectedWorkerId, expectedAttempt, dispatchStartedAt, violations));
        }
        List<ExecutionResult> ordered = new ArrayList<>(concrete.size());
        for (ScenarioTask task : concrete) ordered.add(actualById.get(task.id().value()));
        return new ValidatedWorkUnit(concrete, ordered);
    }

    private void validateConcreteTask(ScenarioTask task, Map<String, ScenarioTask> dispatchedById, List<String> violations) {
        ScenarioTask exact = dispatchedById.get(task.id().value());
        if (exact != null) {
            if (!exact.selector().equals(task.selector()) || !exact.adapterId().equals(task.adapterId())) {
                violations.add("materialized task mutated dispatched identity=" + task.id().value());
            }
            return;
        }
        String parentId = task.metadata().get(META_PARENT_MATERIALIZER_ID);
        ScenarioTask parent = parentId == null ? null : dispatchedById.get(parentId);
        if (parent == null || !isMaterializer(parent)) {
            violations.add("materialized task has no dispatched materializer parent=" + task.id().value());
            return;
        }
        String parentSelector = task.metadata().get(META_PARENT_MATERIALIZER_SELECTOR);
        if (!parent.selector().equals(parentSelector) || !task.selector().startsWith(parent.selector() + "/")) {
            violations.add("materialized task selector is not a descendant of parent=" + task.id().value());
        }
        if (!parent.adapterId().equals(task.adapterId())) violations.add("materialized task changed adapter=" + task.id().value());
        if (!ScenarioIds.from(task.adapterId(), task.selector()).equals(task.id())) violations.add("materialized task id does not match selector=" + task.id().value());
    }

    private boolean isMaterializer(ScenarioTask task) {
        return Boolean.parseBoolean(task.metadata().getOrDefault(META_RUNTIME_MATERIALIZER, "false"));
    }

    private void validateResult(ScenarioTask task, String expectedWorkerId, int expectedAttempt,
                                Instant dispatchStartedAt, ExecutionResult result, List<String> violations) {
        if (result == null) { violations.add("missing result for task '" + task.id().value() + "'"); return; }
        if (!task.id().equals(result.scenarioId())) violations.add("scenario id expected='" + task.id().value() + "' actual='" + result.scenarioId().value() + "'");
        if (!task.displayName().equals(result.displayName())) violations.add("display name does not match task '" + task.id().value() + "'");
        if (!expectedWorkerId.equals(result.workerId().value())) violations.add("result worker id expected='" + expectedWorkerId + "' actual='" + result.workerId().value() + "'");
        if (expectedAttempt != result.attempt()) violations.add("result attempt expected=" + expectedAttempt + " actual=" + result.attempt());
        if (result.attempt() < 1) violations.add("result attempt must be positive");
        if (result.duration().isNegative()) violations.add("duration must not be negative");
        if (result.finishedAt().isBefore(result.startedAt())) violations.add("finishedAt precedes startedAt");
        if (result.startedAt().isBefore(dispatchStartedAt.minusSeconds(1))) violations.add("result started before its dispatch window");
    }

    private List<ExecutionResult> failures(List<ScenarioTask> tasks, String expectedWorkerId,
                                           int expectedAttempt, Instant dispatchStartedAt, List<String> violations) {
        Instant finished = Instant.now();
        String message = "Worker returned a response that violated the dispatch contract: " + String.join("; ", violations);
        return tasks.stream().map(task -> new ExecutionResult(task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                Duration.between(dispatchStartedAt, finished), new WorkerId(expectedWorkerId), expectedAttempt,
                dispatchStartedAt, finished, message, FAILURE_TYPE)).toList();
    }
}
