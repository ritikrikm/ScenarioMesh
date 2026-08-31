package io.scenariomesh.coordinator;

import io.scenariomesh.config.ScenarioMeshConfig.SchedulingMode;
import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.RunId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.workerruntime.DiscoveryMain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScenarioMeshRunner {
    private final DiscoveryInvariantValidator discoveryValidator = new DiscoveryInvariantValidator();
    private final ExecutionHistoryStore history = new ExecutionHistoryStore();
    private final MavenRerunExecutor rerunExecutor = new MavenRerunExecutor();

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
        if (MavenRunOrderSupport.active(request)) {
            logger.progress("Scheduling strategy: Maven class run order with parallel ScenarioMesh dispatch.");
        } else {
            logger.progress("Scheduling strategy: " + request.config().schedulingMode().externalValue() + ".");
        }
        if (request.retryPolicy().rerunsEnabled()) {
            logger.progress("Maven logical reruns enabled: rerunFailingTestsCount="
                    + request.retryPolicy().rerunFailingTestsCount()
                    + ", failOnFlakeCount=" + request.retryPolicy().failOnFlakeCount() + ".");
        }

        MavenRerunExecutor.Execution execution;
        if (request.config().distributed().remote()) {
            try (RemoteWorkerPool workers = preparedRemoteWorkers == null
                    ? new RemoteWorkerPool(request, logger)
                    : new RemoteWorkerPool(request, logger, preparedRemoteWorkers)) {
                execution = rerunExecutor.execute(workers, scheduledTasks, request.retryPolicy(), logger);
                workers.finish();
            }
        } else {
            if (preparedRemoteWorkers != null) {
                preparedRemoteWorkers.close();
                throw new IllegalArgumentException("Prepared remote workers were supplied for a local ScenarioMesh run");
            }
            try (WorkerPool workers = new WorkerPool(request, directory, logger)) {
                execution = rerunExecutor.execute(workers, scheduledTasks, request.retryPolicy(), logger);
                workers.finish();
            }
        }

        List<ExecutionResult> complete = execution.canonicalResults();
        history.update(request.config().reportingDirectory(), execution.logicalTasks(), complete);
        Duration duration = Duration.between(started, Instant.now());
        int flaky = (int) execution.logicalExecutions().stream().filter(logical -> logical.flaky()).count();
        logger.progress("Execution finished: " + complete.size() + " logical terminal result(s) from "
                + discovery.tasks().size() + " discovery task(s), flakes=" + flaky + ", duration=" + duration + ".");
        return new RunOutcome(runId, discovery.adapters(), discovery.tasks(), complete,
                execution.logicalExecutions(), request.retryPolicy(), duration, directory);
    }

    private List<ScenarioTask> prepareForScheduling(RunRequest request, List<ScenarioTask> tasks) {
        if (MavenRunOrderSupport.active(request)) {
            // Native Surefire/Failsafe runOrder is a semantic contract. Do not let ScenarioMesh's
            // duration-aware LPT optimization silently reorder an explicitly Maven-owned run.
            return MavenRunOrderSupport.order(request, withoutDurationEstimates(tasks));
        }
        if (request.config().schedulingMode() == SchedulingMode.HISTORY_LPT) {
            return history.enrich(request.config().reportingDirectory(), tasks);
        }
        return withoutDurationEstimates(tasks);
    }

    private List<ScenarioTask> withoutDurationEstimates(List<ScenarioTask> tasks) {
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
