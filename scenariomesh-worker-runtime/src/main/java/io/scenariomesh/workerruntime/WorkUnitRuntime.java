package io.scenariomesh.workerruntime;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.WorkUnitExecution;
import io.scenariomesh.core.Ports.WorkerTaskCleanup;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** JDK-only work-unit glue shared by persistent workers and class-scoped child JVMs. */
final class WorkUnitRuntime {
    private WorkUnitRuntime() {}

    static WorkUnitExecution execute(AdapterRegistry adapters, List<ScenarioTask> tasks, ExecutionContext context) {
        String adapterId = tasks.get(0).adapterId();
        for (ScenarioTask task : tasks) {
            if (!adapterId.equals(task.adapterId())) {
                return new WorkUnitExecution(tasks, failures(tasks, context,
                        "Worker received a work unit containing multiple adapters", "MixedAdapterWorkUnit"));
            }
        }
        try {
            return java.util.Objects.requireNonNull(adapters.required(adapterId).executeWorkUnit(tasks, context),
                    "Adapter returned null work-unit execution");
        } catch (Exception exception) {
            return new WorkUnitExecution(tasks, failures(tasks, context,
                    safeMessage(exception), exception.getClass().getName()));
        }
    }

    static List<ExecutionResult> cleanup(List<WorkerTaskCleanup> hooks, List<ScenarioTask> tasks,
                                         ExecutionContext context, List<ExecutionResult> results) {
        Map<String, ExecutionResult> byId = new HashMap<>();
        for (ExecutionResult result : results) byId.put(result.scenarioId().value(), result);
        List<ExecutionResult> cleaned = new ArrayList<>(tasks.size());
        for (ScenarioTask task : tasks) {
            ExecutionResult result = byId.get(task.id().value());
            if (result == null) result = failure(task, context,
                    "Adapter did not return a result for this materialized task", "MissingBatchResult");
            cleaned.add(cleanup(hooks, task, context, result));
        }
        return List.copyOf(cleaned);
    }

    private static ExecutionResult cleanup(List<WorkerTaskCleanup> hooks, ScenarioTask task,
                                           ExecutionContext context, ExecutionResult result) {
        for (WorkerTaskCleanup hook : hooks) {
            try {
                hook.afterTask(task, context, result);
            } catch (Exception exception) {
                Instant finished = Instant.now();
                return new ExecutionResult(task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                        Duration.between(result.startedAt(), finished), context.workerId(), context.attempt(),
                        result.startedAt(), finished, cleanupFailureMessage(hook, exception, result),
                        "CleanupFailure:" + exception.getClass().getName());
            }
        }
        return result;
    }

    private static List<ExecutionResult> failures(List<ScenarioTask> tasks, ExecutionContext context,
                                                  String message, String type) {
        return tasks.stream().map(task -> failure(task, context, message, type)).toList();
    }

    private static ExecutionResult failure(ScenarioTask task, ExecutionContext context, String message, String type) {
        Instant now = Instant.now();
        return new ExecutionResult(task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                Duration.ZERO, context.workerId(), context.attempt(), now, now, message, type);
    }

    private static String cleanupFailureMessage(WorkerTaskCleanup hook, Exception cleanupFailure,
                                                ExecutionResult originalResult) {
        StringBuilder message = new StringBuilder().append("Worker cleanup hook ")
                .append(hook.getClass().getName()).append(" failed: ").append(safeMessage(cleanupFailure))
                .append(". Original task outcome: status=").append(originalResult.status());
        if (originalResult.failureType() != null && !originalResult.failureType().isBlank()) {
            message.append(", failureType=").append(originalResult.failureType());
        }
        if (originalResult.failureMessage() != null && !originalResult.failureMessage().isBlank()) {
            message.append(", failureMessage=").append(originalResult.failureMessage());
        }
        return message.toString();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getName() : message;
    }
}
