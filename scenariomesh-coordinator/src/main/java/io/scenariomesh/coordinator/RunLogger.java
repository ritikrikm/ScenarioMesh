package io.scenariomesh.coordinator;

import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.core.Domain.ExecutionResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;

/** Centralizes human and machine-readable runtime logging. */
final class RunLogger {
    private final ScenarioMeshConfig config;
    private final String runId;
    private final Path eventsFile;

    RunLogger(ScenarioMeshConfig config) {
        this(config, null, null);
    }

    RunLogger(ScenarioMeshConfig config, String runId, Path runDirectory) {
        this.config = Objects.requireNonNull(config, "config");
        this.runId = runId;
        this.eventsFile = runDirectory == null ? null : runDirectory.resolve("events.jsonl");
    }

    synchronized void info(String message) {
        System.out.println("[ScenarioMesh] " + message);
        event("INFO", null, null, message);
    }

    synchronized void progress(String message) {
        if (config.showProgress()) System.out.println("[ScenarioMesh] " + message);
        event("PROGRESS", null, null, message);
    }

    synchronized void workerOutput(String workerId, String line) {
        if (config.liveConsoleLogs()) System.out.println("[ScenarioMesh][" + workerId + "] " + line);
        event("WORKER_OUTPUT", workerId, null, line);
    }

    synchronized void workerCompleted(String workerId, ExecutionResult result, int completed, int failed, int busy, int total) {
        if (config.showProgress()) {
            int queued = Math.max(0, total - completed - busy);
            String status = result.status().name();
            System.out.println("[ScenarioMesh] " + workerId + " " + status + " " + result.displayName()
                    + " | completed=" + completed + "/" + total
                    + " failed=" + failed + " busy=" + busy + " queued=" + queued);
        }
        event("TASK_COMPLETED", workerId, result.scenarioId().value(),
                "status=" + result.status().name() + ",attempt=" + result.attempt()
                        + ",durationMillis=" + result.duration().toMillis());
    }

    private void event(String type, String workerId, String taskId, String message) {
        if (eventsFile == null) return;
        String json = "{"
                + "\"timestamp\":\"" + escape(Instant.now().toString()) + "\","
                + "\"runId\":" + nullable(runId) + ","
                + "\"type\":\"" + escape(type) + "\","
                + "\"workerId\":" + nullable(workerId) + ","
                + "\"taskId\":" + nullable(taskId) + ","
                + "\"message\":\"" + escape(message) + "\"}"
                + System.lineSeparator();
        try {
            Files.writeString(eventsFile, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception exception) {
            // Observability must never alter test outcomes. Console remains available.
            if (config.showProgress()) {
                System.err.println("[ScenarioMesh] Failed to append structured event log: " + exception.getMessage());
            }
        }
    }

    private String nullable(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
