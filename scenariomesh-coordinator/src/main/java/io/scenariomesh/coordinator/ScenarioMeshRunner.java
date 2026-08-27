package io.scenariomesh.coordinator;

import io.scenariomesh.config.ScenarioMeshConfig.SchedulingMode;
import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.RunId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.workerruntime.DiscoveryMain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ScenarioMeshRunner {
    private static final String META_RUNTIME_MATERIALIZER = "runtimeMaterializer";
    private final DiscoveryInvariantValidator discoveryValidator = new DiscoveryInvariantValidator();
    private final ExecutionHistoryStore history = new ExecutionHistoryStore();

    public RunOutcome run(RunRequest request) throws Exception {
        return run(request, null);
    }

    public RunOutcome run(RunRequest request, PreparedRemoteWorkers preparedRemoteWorkers) throws Exception {
        RunId runId = RunId.create();
        Path directory = request.config().reportingDirectory().resolve("runs").resolve(runId.value());
        Files.createDirectories(directory);
        RunLogger logger = new RunLogger(request.config(), runId.value(), directory);
        Instant started = Instant.now();

        logger.progress("Run " + runId.value() + " discovering executable tests...");
        DiscoveryMain.DiscoveryResult discovery = new DiscoveryProcess().discover(request, directory);
        discoveryValidator.validate(discovery.adapters(), discovery.tasks());
        List<ScenarioTask> scheduledTasks = prepareForScheduling(request, discovery.tasks());
        logger.progress("Adapter selected: " + String.join(", ", discovery.adapters()));
        logger.progress("Discovery produced " + discovery.tasks().size() + " executable task(s).");
        logger.progress("Scheduling strategy: " + request.config().schedulingMode().externalValue() + ".");

        List<ExecutionResult> results;
        if (request.config().distributed().remote()) {
            try (RemoteWorkerPool workers = preparedRemoteWorkers == null
                    ? new RemoteWorkerPool(request, logger)
                    : new RemoteWorkerPool(request, logger, preparedRemoteWorkers)) {
                results = workers.execute(scheduledTasks);
            }
        } else {
            if (preparedRemoteWorkers != null) {
                preparedRemoteWorkers.close();
                throw new IllegalArgumentException("Prepared remote workers were supplied for a local ScenarioMesh run");
            }
            try (WorkerPool workers = new WorkerPool(request, directory, logger)) {
                results = workers.execute(scheduledTasks);
            }
        }

        Set<String> completed = new HashSet<>();
        for (ExecutionResult result : results) {
            String resultId = result.scenarioId().value();
            if (!completed.add(resultId)) {
                throw new IllegalStateException(
                        "Worker execution produced more than one terminal result for task '" + resultId + "'");
            }
        }

        List<ExecutionResult> complete = new ArrayList<>(results);
        for (ScenarioTask task : discovery.tasks()) {
            boolean materializer = Boolean.parseBoolean(
                    task.metadata().getOrDefault(META_RUNTIME_MATERIALIZER, "false"));
            if (!materializer && !completed.contains(task.id().value())) {
                Instant now = Instant.now();
                complete.add(new ExecutionResult(
                        task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                        Duration.ZERO, new WorkerId("coordinator"), 1, now, now,
                        "No worker produced a terminal result for this task", "MissingResult"));
            }
        }

        history.update(request.config().reportingDirectory(), scheduledTasks, complete);
        Duration duration = Duration.between(started, Instant.now());
        logger.progress("Execution finished: " + complete.size() + " terminal result(s) from "
                + discovery.tasks().size() + " discovery task(s), duration=" + duration + ".");
        return new RunOutcome(runId, discovery.adapters(), discovery.tasks(), complete, duration, directory);
    }

    private List<ScenarioTask> prepareForScheduling(RunRequest request, List<ScenarioTask> tasks) {
        if (request.config().schedulingMode() == SchedulingMode.HISTORY_LPT) {
            return history.enrich(request.config().reportingDirectory(), tasks);
        }
        List<ScenarioTask> fifo = new ArrayList<>(tasks.size());
        for (ScenarioTask task : tasks) {
            if (!task.metadata().containsKey(ExecutionHistoryStore.ESTIMATED_DURATION_MILLIS)) {
                fifo.add(task);
                continue;
            }
            Map<String, String> metadata = new LinkedHashMap<>(task.metadata());
            metadata.remove(ExecutionHistoryStore.ESTIMATED_DURATION_MILLIS);
            fifo.add(new ScenarioTask(task.id(), task.displayName(), task.adapterId(), task.framework(),
                    task.source(), task.line(), task.selector(), task.tags(), metadata));
        }
        return List.copyOf(fifo);
    }
}
