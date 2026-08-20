package io.scenariomesh.config;

import io.scenariomesh.config.ScenarioMeshConfig.AdapterMismatchPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigResolverTest {
    @TempDir
    Path project;

    private final ConfigResolver resolver = new ConfigResolver();

    @Test
    void usesDocumentedDefaultsWithoutAFile() {
        ScenarioMeshConfig config = resolver.resolve(project, project.resolve("target"), Map.of(), Map.of());

        assertTrue(config.enabled());
        assertEquals("auto", config.executionAdapter());
        assertEquals(AdapterMismatchPolicy.FAIL, config.adapterMismatchPolicy());
        assertEquals(0, config.infrastructureRetries());
        assertEquals(4, config.workerCount());
        assertEquals(4, config.minimumReadyWorkers());
        assertEquals(0, config.maxTasksPerWorker());
        assertEquals(0, config.maxHeapUsagePercent());
        assertEquals(Duration.ofMinutes(2), config.discoveryTimeout());
        assertEquals(Duration.ofMinutes(15), config.workerTaskTimeout());
        assertEquals(project.resolve("target/scenariomesh").toAbsolutePath().normalize(), config.reportingDirectory());
        assertTrue(config.liveConsoleLogs());
        assertTrue(config.workerLogFiles());
        assertTrue(config.showConfiguration());
        assertTrue(config.showProgress());
    }

    @Test
    void loadsVersionedYamlAndNestedValues() throws Exception {
        Files.writeString(project.resolve("scenariomesh.yml"), """
                scenariomesh:
                  configVersion: 1
                  enabled: false
                  execution:
                    adapter: cucumber-junit4
                    adapterMismatchPolicy: use-detected
                    infrastructureRetries: 2
                  workers:
                    count: 7
                    minimumReady: 4
                    maxTasksPerWorker: 50
                    maxHeapUsagePercent: 85
                    startupTimeout: PT45S
                    taskTimeout: PT5M
                    shutdownTimeout: PT15S
                    jvmArgs:
                      - -Xmx1g
                      - -Dfile.encoding=UTF-8
                  discovery:
                    timeout: PT3M
                  reporting:
                    directory: reports/scenariomesh
                  logging:
                    liveConsole: false
                    workerFiles: true
                    showConfiguration: false
                    showProgress: false
                """);

        ConfigResolver.ConfigResolution resolution = resolver.resolveDetailed(
                project, project.resolve("target"), Map.of(), Map.of());
        ScenarioMeshConfig config = resolution.config();

        assertFalse(config.enabled());
        assertEquals("cucumber-junit4", config.executionAdapter());
        assertEquals(AdapterMismatchPolicy.USE_DETECTED, config.adapterMismatchPolicy());
        assertEquals(2, config.infrastructureRetries());
        assertEquals(7, config.workerCount());
        assertEquals(4, config.minimumReadyWorkers());
        assertEquals(50, config.maxTasksPerWorker());
        assertEquals(85, config.maxHeapUsagePercent());
        assertEquals(Duration.ofSeconds(45), config.workerStartupTimeout());
        assertEquals(Duration.ofMinutes(5), config.workerTaskTimeout());
        assertEquals(Duration.ofSeconds(15), config.workerShutdownTimeout());
        assertEquals(Duration.ofMinutes(3), config.discoveryTimeout());
        assertEquals(2, config.workerJvmArgs().size());
        assertEquals(project.resolve("reports/scenariomesh").toAbsolutePath().normalize(), config.reportingDirectory());
        assertFalse(config.liveConsoleLogs());
        assertTrue(config.workerLogFiles());
        assertFalse(config.showConfiguration());
        assertFalse(config.showProgress());
        assertEquals(project.resolve("scenariomesh.yml").toAbsolutePath().normalize(), resolution.configFile().orElseThrow());
    }

    @Test
    void minimumReadyDefaultsToResolvedWorkerCount() {
        ScenarioMeshConfig config = resolver.resolve(
                project,
                project.resolve("target"),
                Map.of("scenariomesh.workers.count", "9"),
                Map.of());

        assertEquals(9, config.workerCount());
        assertEquals(9, config.minimumReadyWorkers());
    }

    @Test
    void resolvesPropertyThenEnvironmentThenYamlThenDefaults() throws Exception {
        Files.writeString(project.resolve("scenariomesh.yml"), """
                scenariomesh:
                  configVersion: 1
                  workers:
                    count: 5
                    taskTimeout: PT7M
                  execution:
                    adapter: testng
                  logging:
                    liveConsole: false
                """);

        ScenarioMeshConfig config = resolver.resolve(
                project,
                project.resolve("target"),
                Map.of(
                        "scenariomesh.workers.count", "9",
                        "scenariomesh.workers.taskTimeout", "PT3M",
                        "scenariomesh.logging.liveConsole", "true"),
                Map.of(
                        "SCENARIOMESH_WORKERS_COUNT", "8",
                        "SCENARIOMESH_WORKERS_TASK_TIMEOUT", "PT4M",
                        "SCENARIOMESH_EXECUTION_ADAPTER", "junit-platform"));

        assertEquals(9, config.workerCount());
        assertEquals(9, config.minimumReadyWorkers());
        assertEquals(Duration.ofMinutes(3), config.workerTaskTimeout());
        assertEquals("junit-platform", config.executionAdapter());
        assertTrue(config.liveConsoleLogs());
    }

    @Test
    void keepsLegacyWorkerPropertyAsAlias() {
        ScenarioMeshConfig config = resolver.resolve(
                project,
                project.resolve("target"),
                Map.of("scenariomesh.workers", "6"),
                Map.of());

        assertEquals(6, config.workerCount());
        assertEquals(6, config.minimumReadyWorkers());
    }

    @Test
    void keepsLegacyWorkerTaskTimeoutPropertyAsAlias() {
        ScenarioMeshConfig config = resolver.resolve(
                project,
                project.resolve("target"),
                Map.of("scenariomesh.worker.taskTimeout", "PT90S"),
                Map.of());

        assertEquals(Duration.ofSeconds(90), config.workerTaskTimeout());
    }

    @Test
    void rejectsInvalidResilienceRanges() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(project, project.resolve("target"),
                        Map.of("scenariomesh.execution.infrastructureRetries", "-1"), Map.of()))
                .getMessage().contains("infrastructureRetries"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(project, project.resolve("target"),
                        Map.of("scenariomesh.workers.count", "2", "scenariomesh.workers.minimumReady", "3"), Map.of()))
                .getMessage().contains("minimumReady"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(project, project.resolve("target"),
                        Map.of("scenariomesh.workers.maxTasksPerWorker", "-1"), Map.of()))
                .getMessage().contains("maxTasksPerWorker"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(project, project.resolve("target"),
                        Map.of("scenariomesh.workers.maxHeapUsagePercent", "101"), Map.of()))
                .getMessage().contains("maxHeapUsagePercent"));
    }

    @Test
    void rejectsNonPositiveWorkerTaskTimeout() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(
                        project,
                        project.resolve("target"),
                        Map.of("scenariomesh.workers.taskTimeout", "PT0S"),
                        Map.of()));

        assertTrue(error.getMessage().contains("workers.taskTimeout"));
    }

    @Test
    void rejectsWorkerSocketTimeoutsThatCannotBeRepresentedBySocketApi() {
        String tooLarge = Duration.ofMillis((long) Integer.MAX_VALUE + 1L).toString();

        for (String property : new String[]{
                "scenariomesh.workers.startupTimeout",
                "scenariomesh.workers.taskTimeout",
                "scenariomesh.workers.shutdownTimeout"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> resolver.resolve(
                            project,
                            project.resolve("target"),
                            Map.of(property, tooLarge),
                            Map.of()));

            assertTrue(error.getMessage().contains("must not exceed"));
        }
    }

    @Test
    void rejectsUnknownYamlKeysInsteadOfSilentlyIgnoringTypos() throws Exception {
        Files.writeString(project.resolve("scenariomesh.yml"), """
                scenariomesh:
                  configVersion: 1
                  workers:
                    cout: 8
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(project, project.resolve("target"), Map.of(), Map.of()));
        assertTrue(error.getMessage().contains("workers.cout"));
    }

    @Test
    void rejectsAmbiguousDefaultConfigFiles() throws Exception {
        String body = "scenariomesh:\n  configVersion: 1\n";
        Files.writeString(project.resolve("scenariomesh.yml"), body);
        Files.writeString(project.resolve("scenariomesh.yaml"), body);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(project, project.resolve("target"), Map.of(), Map.of()));
        assertTrue(error.getMessage().contains("Both scenariomesh.yml and scenariomesh.yaml"));
    }

    @Test
    void rejectsUnsupportedConfigVersion() throws Exception {
        Files.writeString(project.resolve("scenariomesh.yml"), """
                scenariomesh:
                  configVersion: 2
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(project, project.resolve("target"), Map.of(), Map.of()));
        assertTrue(error.getMessage().contains("unsupported configVersion"));
    }
}
