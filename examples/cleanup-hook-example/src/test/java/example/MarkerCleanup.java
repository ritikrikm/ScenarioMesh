package example;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.WorkerTaskCleanup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class MarkerCleanup implements WorkerTaskCleanup {
    @Override
    public void afterTask(ScenarioTask task, ExecutionContext context, ExecutionResult result) throws Exception {
        Path marker = Path.of("target", "cleanup-hook.log");
        Files.createDirectories(marker.getParent());
        Files.writeString(
                marker,
                task.displayName() + "|" + context.workerId().value() + "|attempt=" + context.attempt() + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}
