package io.scenariomesh.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConfigProvenanceTest {
    @TempDir Path project;

    @Test
    void reportsWinningSourceWithoutRecordingValues() throws Exception {
        Files.writeString(project.resolve("scenariomesh.yml"), """
                scenariomesh:
                  configVersion: 1
                  workers:
                    count: 5
                    taskTimeout: PT7M
                  logging:
                    showProgress: false
                """);

        ConfigResolver.ConfigResolution resolution = new ConfigResolver().resolveDetailed(
                project,
                project.resolve("target"),
                Map.of("scenariomesh.workers.count", "9"),
                Map.of("SCENARIOMESH_WORKERS_TASK_TIMEOUT", "PT4M"));

        assertEquals("property:scenariomesh.workers.count",
                resolution.sourceOf("scenariomesh.workers.count"));
        assertEquals("environment:SCENARIOMESH_WORKERS_TASK_TIMEOUT",
                resolution.sourceOf("scenariomesh.workers.taskTimeout"));
        assertEquals("yaml:logging.showProgress",
                resolution.sourceOf("scenariomesh.logging.showProgress"));
        assertEquals("default", resolution.sourceOf("scenariomesh.logging.workerFiles"));
    }

    @Test
    void provenanceNeverContainsSecretValues() {
        String token = "super-secret-token-value";
        ConfigResolver.ConfigResolution resolution = new ConfigResolver().resolveDetailed(
                project,
                project.resolve("target"),
                Map.of("scenariomesh.distributed.token", token),
                Map.of());

        assertEquals("property:scenariomesh.distributed.token",
                resolution.sourceOf("scenariomesh.distributed.token"));
        assertFalse(resolution.provenance().toString().contains(token));
    }
}
