package io.scenariomesh.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLoggerTest {
    @TempDir
    Path directory;

    @Test
    void emitsParseableCorrelatedJsonlEvents() throws Exception {
        ScenarioMeshConfig config = ScenarioMeshConfig.defaults(directory);
        RunLogger logger = new RunLogger(config, "run-123", directory);
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        ExecutionResult result = new ExecutionResult(new ScenarioId("task-7"), "checkout works",
                ResultStatus.PASSED, Duration.ofMillis(42), new WorkerId("worker-2"), 1,
                start, start.plusMillis(42), null, null);

        logger.progress("scheduler ready");
        logger.workerCompleted("worker-2", result, 1, 0, 0, 1);

        Path events = directory.resolve("events.jsonl");
        assertTrue(Files.isRegularFile(events));
        List<String> lines = Files.readAllLines(events);
        assertEquals(2, lines.size());
        ObjectMapper mapper = JsonCodec.create();
        JsonNode progress = mapper.readTree(lines.get(0));
        JsonNode completed = mapper.readTree(lines.get(1));
        assertEquals("run-123", progress.path("runId").asText());
        assertEquals("PROGRESS", progress.path("type").asText());
        assertEquals("TASK_COMPLETED", completed.path("type").asText());
        assertEquals("worker-2", completed.path("workerId").asText());
        assertEquals("task-7", completed.path("taskId").asText());
        assertTrue(completed.path("message").asText().contains("durationMillis=42"));
    }
}
