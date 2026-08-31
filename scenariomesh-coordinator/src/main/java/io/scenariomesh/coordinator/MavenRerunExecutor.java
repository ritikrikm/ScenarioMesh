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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes Maven-compatible test rerun rounds without rediscovery or duplicate logical identities. */
final class MavenRerunExecutor {
    Execution execute(TaskExecutionPool pool, List<ScenarioTask> discoveryTasks,
                      RetryPolicy policy, RunLogger logger) throws InterruptedException {
        TaskExecutionPool.RoundExecution initial = pool.executeRound(List.copyOf(discoveryTasks));
        Round initialRound = validateRound(initial, null);

        List<ScenarioTask> logicalTasks = initialRound.tasks().values().stream()
                .sorted(Comparator.comparing(task -> task.id().value())).toList();
        Map<ScenarioId, ScenarioTask> taskById = new LinkedHashMap<>();
        Map<ScenarioId, List<ExecutionAttempt>> attempts = new LinkedHashMap<>();
        for (ScenarioTask task : logicalTasks) {
            taskById.put(task.id(), task);
            ExecutionResult result = initialRound.results().get(task.id());
            attempts.put(task.id(), new ArrayList<>(List.of(
                    new ExecutionAttempt(task.id(), 0, RetryCause.INITIAL, result))));
        }

        List<ScenarioTask> roundTasks = failedTasks(logicalTasks, initialRound.results());
        for (int rerunIndex = 1;
             rerunIndex <= policy.rerunFailingTestsCount() && !roundTasks.isEmpty();
             rerunIndex++) {
            logger.mavenRerunRound(rerunIndex, roundTasks.size(), policy.rerunFailingTestsCount());
            TaskExecutionPool.RoundExecution rawRound = pool.executeRound(roundTasks);
            Round round = validateRound(rawRound, roundTasks);
            for (ScenarioTask requested : roundTasks) {
                ExecutionResult result = round.results().get(requested.id());
                attempts.get(requested.id()).add(new ExecutionAttempt(
                        requested.id(), rerunIndex, RetryCause.MAVEN_RERUN, result));
            }
            roundTasks = failedTasks(roundTasks, round.results());
        }

        List<LogicalExecution> logical = new ArrayList<>(logicalTasks.size());
        List<ExecutionResult> canonical = new ArrayList<>(logicalTasks.size());
        for (ScenarioTask task : logicalTasks) {
            LogicalExecution execution = RetrySemantics.aggregate(attempts.get(task.id()));
            logical.add(execution);
            canonical.add(execution.canonicalResult());
        }
        return new Execution(List.copyOf(logicalTasks), List.copyOf(canonical), List.copyOf(logical));
    }

    private List<ScenarioTask> failedTasks(List<ScenarioTask> tasks, Map<ScenarioId, ExecutionResult> results) {
        List<ScenarioTask> failed = new ArrayList<>();
        for (ScenarioTask task : tasks) {
            ExecutionResult result = results.get(task.id());
            if (result != null && result.status() == ResultStatus.TEST_FAILURE) failed.add(task);
        }
        return List.copyOf(failed);
    }

    /**
     * Initial execution may replace runtime materializer placeholders with concrete test identities.
     * Every later round must preserve the exact concrete identity it was asked to rerun.
     */
    private Round validateRound(TaskExecutionPool.RoundExecution round, List<ScenarioTask> requestedConcreteTasks) {
        Map<ScenarioId, ScenarioTask> tasks = new LinkedHashMap<>();
        for (ScenarioTask task : round.tasks()) {
            if (tasks.put(task.id(), task) != null) {
                throw new IllegalStateException("execution round returned duplicate concrete task " + task.id().value());
            }
        }
        Map<ScenarioId, ExecutionResult> results = new LinkedHashMap<>();
        for (ExecutionResult result : round.results()) {
            if (!tasks.containsKey(result.scenarioId())) {
                throw new IllegalStateException("execution round returned result without concrete task "
                        + result.scenarioId().value());
            }
            if (results.put(result.scenarioId(), result) != null) {
                throw new IllegalStateException("execution round returned duplicate terminal result for "
                        + result.scenarioId().value());
            }
        }
        if (tasks.size() != results.size()) {
            throw new IllegalStateException("execution round concrete task/result count mismatch: tasks="
                    + tasks.size() + " results=" + results.size());
        }
        for (ScenarioTask task : tasks.values()) {
            if (!results.containsKey(task.id())) {
                throw new IllegalStateException("execution round returned no terminal result for " + task.id().value());
            }
        }
        if (requestedConcreteTasks != null) {
            Map<ScenarioId, ScenarioTask> expected = new LinkedHashMap<>();
            for (ScenarioTask task : requestedConcreteTasks) expected.put(task.id(), task);
            if (!expected.keySet().equals(tasks.keySet())) {
                throw new IllegalStateException("Maven rerun changed concrete logical test identity; expected="
                        + expected.keySet() + " actual=" + tasks.keySet());
            }
        }
        return new Round(Map.copyOf(tasks), Map.copyOf(results));
    }

    private record Round(Map<ScenarioId, ScenarioTask> tasks, Map<ScenarioId, ExecutionResult> results) {}

    record Execution(List<ScenarioTask> logicalTasks,
                     List<ExecutionResult> canonicalResults,
                     List<LogicalExecution> logicalExecutions) {
        Execution {
            logicalTasks = List.copyOf(logicalTasks);
            canonicalResults = List.copyOf(canonicalResults);
            logicalExecutions = List.copyOf(logicalExecutions);
        }
    }
}
