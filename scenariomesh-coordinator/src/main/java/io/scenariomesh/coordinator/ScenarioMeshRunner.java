package io.scenariomesh.coordinator;

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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class ScenarioMeshRunner {
    private final DiscoveryInvariantValidator discoveryValidator = new DiscoveryInvariantValidator();

    public RunOutcome run(RunRequest request) throws Exception {
        RunId runId = RunId.create();
        Path directory = request.config().reportingDirectory().resolve("runs").resolve(runId.value());
        Files.createDirectories(directory);
        RunLogger logger = new RunLogger(request.config());
        Instant started = Instant.now();

        logger.progress("Run " + runId.value() + " discovering executable tests...");
        DiscoveryMain.DiscoveryResult discovery = new DiscoveryProcess().discover(request, directory);
        discoveryValidator.validate(discovery.adapters(), discovery.tasks());
        logger.progress("Adapter selected: " + String.join(", ", discovery.adapters()));
        logger.progress("Discovery produced " + discovery.tasks().size() + " executable task(s).");

        List<ExecutionResult> results;
        try (WorkerPool workers = new WorkerPool(request, directory, logger)) {
            results = workers.execute(discovery.tasks());
        }

        Set<String> discoveredIds = discovery.tasks().stream()
                .map(task -> task.id().value())
                .collect(Collectors.toUnmodifiableSet());
        Set<String> completed = new HashSet<>();
        for (ExecutionResult result : results) {
            String resultId = result.scenarioId().value();
            if (!discoveredIds.contains(resultId)) {
                throw new IllegalStateException(
                        "Worker execution produced a terminal result for undiscovered task '" + resultId + "'");
            }
            if (!completed.add(resultId)) {
                throw new IllegalStateException(
                        "Worker execution produced more than one terminal result for task '" + resultId + "'");
            }
        }

        List<ExecutionResult> complete = new ArrayList<>(results);
        for (ScenarioTask task : discovery.tasks()) {
            if (!completed.contains(task.id().value())) {
                Instant now = Instant.now();
                complete.add(new ExecutionResult(
                        task.id(),
                        task.displayName(),
                        ResultStatus.INFRASTRUCTURE_FAILURE,
                        Duration.ZERO,
                        new WorkerId("coordinator"),
                        1,
                        now,
                        now,
                        "No worker produced a terminal result for this task",
                        "MissingResult"));
            }
        }

        Duration duration = Duration.between(started, Instant.now());
        logger.progress("Execution finished: " + complete.size() + "/" + discovery.tasks().size()
                + " terminal result(s), duration=" + duration + ".");
        return new RunOutcome(runId, discovery.adapters(), discovery.tasks(), complete, duration, directory);
    }
}
