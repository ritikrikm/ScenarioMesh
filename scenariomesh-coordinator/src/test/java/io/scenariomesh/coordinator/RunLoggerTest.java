package io.scenariomesh.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.config.DistributedConfig;
import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.workerruntime.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLoggerTest {
    @TempDir Path directory;

    @Test
    void emitsParseableTypedCorrelatedJsonlEvents() throws Exception {
        ScenarioMeshConfig config = ScenarioMeshConfig.defaults(directory);
        RunLogger logger = new RunLogger(config, "run-123", directory);
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        ExecutionResult result = new ExecutionResult(new ScenarioId("task-7"), "checkout works",
                ResultStatus.PASSED, Duration.ofMillis(42), new WorkerId("worker-2"), 1,
                start, start.plusMillis(42), null, null);

        logger.progress("scheduler ready");
        logger.workerCompleted("worker-2", result, 1, 0, 0, 1);

        List<String> lines = Files.readAllLines(directory.resolve("events.jsonl"));
        assertEquals(2, lines.size());
        ObjectMapper mapper = JsonCodec.create();
        JsonNode progress = mapper.readTree(lines.get(0));
        JsonNode completed = mapper.readTree(lines.get(1));
        assertEquals("run-123", progress.path("runId").asText());
        assertEquals("PROGRESS", progress.path("type").asText());
        assertEquals("TASK_COMPLETED", completed.path("type").asText());
        assertEquals("worker-2", completed.path("workerId").asText());
        assertEquals("task-7", completed.path("taskId").asText());
        assertEquals(1, completed.path("attempt").asInt());
        assertEquals(42L, completed.path("durationMillis").asLong());
        assertEquals(0, completed.path("queueDepth").asInt());
        assertEquals(0, completed.path("busyWorkers").asInt());
    }

    @Test
    void redactsConfiguredSecretsAndBoundsMessages() throws Exception {
        ScenarioMeshConfig defaults = ScenarioMeshConfig.defaults(directory);
        String secret = "super-secret-token-value";
        DistributedConfig distributed = new DistributedConfig(
                DistributedConfig.WorkerMode.REMOTE, "127.0.0.1", 4444, secret,
                Duration.ofSeconds(2), defaults.distributed().tls());
        ScenarioMeshConfig config = new ScenarioMeshConfig(
                defaults.enabled(), defaults.executionAdapter(), defaults.adapterMismatchPolicy(),
                defaults.infrastructureRetries(), defaults.workerCount(), defaults.minimumReadyWorkers(),
                defaults.maxTasksPerWorker(), defaults.maxHeapUsagePercent(), defaults.discoveryTimeout(),
                defaults.workerStartupTimeout(), defaults.workerTaskTimeout(), defaults.workerShutdownTimeout(),
                defaults.reportingDirectory(), defaults.workerJvmArgs(), defaults.liveConsoleLogs(),
                defaults.workerLogFiles(), defaults.showConfiguration(), defaults.showProgress(), distributed);
        RunLogger logger = new RunLogger(config, "run-secret", directory);
        logger.progress("token=" + secret + " " + "x".repeat(20_000));

        String line = Files.readString(directory.resolve("events.jsonl"));
        assertFalse(line.contains(secret));
        assertTrue(line.contains("***"));
        JsonNode event = JsonCodec.create().readTree(line);
        assertTrue(event.path("message").asText().length() < 17_000);
        assertTrue(event.path("message").asText().endsWith("...[truncated]"));
    }
}
