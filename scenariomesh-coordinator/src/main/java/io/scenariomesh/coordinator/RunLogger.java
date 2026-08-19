package io.scenariomesh.coordinator;

import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.core.Domain.ExecutionResult;

import java.util.Objects;

/**
 * Centralizes ScenarioMesh runtime console formatting so worker/coordinator code
 * does not duplicate logging policy or prefixes.
 */
final class RunLogger {
    private final ScenarioMeshConfig config;

    RunLogger(ScenarioMeshConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    synchronized void info(String message) {
        System.out.println("[ScenarioMesh] " + message);
    }

    synchronized void progress(String message) {
        if (config.showProgress()) {
            System.out.println("[ScenarioMesh] " + message);
        }
    }

    synchronized void workerOutput(String workerId, String line) {
        if (config.liveConsoleLogs()) {
            System.out.println("[ScenarioMesh][" + workerId + "] " + line);
        }
    }

    synchronized void workerCompleted(String workerId, ExecutionResult result, int completed, int failed, int busy, int total) {
        if (!config.showProgress()) {
            return;
        }
        int queued = Math.max(0, total - completed - busy);
        String status = result.status().name();
        System.out.println("[ScenarioMesh] " + workerId + " " + status + " " + result.displayName()
                + " | completed=" + completed + "/" + total
                + " failed=" + failed
                + " busy=" + busy
                + " queued=" + queued);
    }
}
