package io.scenariomesh.coordinator;

import io.scenariomesh.config.ScenarioMeshConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Drains one worker's merged stdout/stderr stream. Output can be mirrored to the
 * live console, persisted per worker, both, or neither. The stream is always
 * consumed so a verbose target framework cannot block the worker process.
 */
final class WorkerOutputPump implements Runnable {
    private final String workerId;
    private final Process process;
    private final ScenarioMeshConfig config;
    private final Path logFile;
    private final RunLogger logger;

    WorkerOutputPump(String workerId, Process process, ScenarioMeshConfig config, Path logFile, RunLogger logger) {
        this.workerId = workerId;
        this.process = process;
        this.config = config;
        this.logFile = logFile;
        this.logger = logger;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = config.workerLogFiles()
                     ? Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)
                     : null) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (writer != null) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                }
                logger.workerOutput(workerId, line);
            }
        } catch (IOException exception) {
            logger.info("Unable to consume output for " + workerId + ": " + exception.getMessage());
        }
    }
}
