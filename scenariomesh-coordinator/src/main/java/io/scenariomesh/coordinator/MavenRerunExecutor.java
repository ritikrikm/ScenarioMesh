package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.RetrySemantics;
import io.scenariomesh.core.RetrySemantics.ExecutionAttempt;
import io.scenariomesh.core.RetrySemantics.LogicalExecution;
import io.scenariomesh.core.RetrySemantics.RetryCause;
import io.scenariomesh.core.RetrySemantics.RetryPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes Maven-compatible test rerun rounds without rediscovery or duplicate logical identities. */
final class MavenRerunExecutor {
    Execution execute(TaskExecutionPool pool, List<ScenarioTask> originalTasks,
                      RetryPolicy policy, RunLogger logger) throws InterruptedException {
        List<ScenarioTask> tasks = List.copyOf(originalTasks);
        Map<ScenarioId, ScenarioTask> taskById = new LinkedHashMap<>();
        Map<ScenarioId, List<ExecutionAttempt>> attempts = new LinkedHashMap<>();
        for (ScenarioTask task : tasks) {
            if (taskById.put(task.id(), task) != null) {
                throw new IllegalArgumentException("duplicate logical task id " + task.id().value());
            }
            attempts.put(task.id(), new ArrayList<>());
        }

        List<ScenarioTask> roundTasks = tasks;
        for (int rerunIndex = 0; !roundTasks.isEmpty(); rerunIndex++) {
            if (rerunIndex > policy.rerunFailingTestsCount()) break;
            if (rerunIndex > 0) {
                logger.mavenRerunRound(rerunIndex, roundTasks.size(), policy.rerunFailingTestsCount());
            }
            List<ExecutionResult> roundResults = pool.executeRound(roundTasks);
            Map<ScenarioId, ExecutionResult> byId = validateRound(roundTasks, roundResults);
            for (ScenarioTask task : roundTasks) {
                ExecutionResult result = byId.get(task.id());
                attempts.get(task.id()).add(new ExecutionAttempt(
                        task.id(), rerunIndex,
                        rerunIndex == 0 ? RetryCause.INITIAL : RetryCause.MAVEN_RERUN,
                        result));
            }

            if (rerunIndex >= policy.rerunFailingTestsCount()) break;
            List<ScenarioTask> next = new ArrayList<>();
            for (ScenarioTask task : roundTasks) {
                ExecutionResult result = byId.get(task.id());
                if (result.status() == ResultStatus.TEST_FAILURE) next.add(taskById.get(task.id()));
            }
            roundTasks = List.copyOf(next);
        }

        List<LogicalExecution> logical = new ArrayList<>(tasks.size());
        List<ExecutionResult> canonical = new ArrayList<>(tasks.size());
        for (ScenarioTask task : tasks) {
            List<ExecutionAttempt> taskAttempts = attempts.get(task.id());
            if (taskAttempts == null || taskAttempts.isEmpty()) {
                throw new IllegalStateException("no execution attempt was produced for logical task " + task.id().value());
            }
            LogicalExecution execution = RetrySemantics.aggregate(taskAttempts);
            logical.add(execution);
            canonical.add(execution.canonicalResult());
        }
        return new Execution(List.copyOf(canonical), List.copyOf(logical));
    }

    private Map<ScenarioId, ExecutionResult> validateRound(
            List<ScenarioTask> tasks, List<ExecutionResult> results) {
        Map<ScenarioId, ScenarioTask> expected = new LinkedHashMap<>();
        for (ScenarioTask task : tasks) expected.put(task.id(), task);
        Map<ScenarioId, ExecutionResult> actual = new LinkedHashMap<>();
        for (ExecutionResult result : results) {
            if (!expected.containsKey(result.scenarioId())) {
                throw new IllegalStateException("execution round returned unexpected task " + result.scenarioId().value());
            }
            if (actual.put(result.scenarioId(), result) != null) {
                throw new IllegalStateException("execution round returned duplicate terminal result for "
                        + result.scenarioId().value());
            }
        }
        for (ScenarioTask task : tasks) {
            if (!actual.containsKey(task.id())) {
                throw new IllegalStateException("execution round returned no terminal result for " + task.id().value());
            }
        }
        return actual;
    }

    record Execution(List<ExecutionResult> canonicalResults, List<LogicalExecution> logicalExecutions) {
        Execution {
            canonicalResults = List.copyOf(canonicalResults);
            logicalExecutions = List.copyOf(logicalExecutions);
        }
    }
}
