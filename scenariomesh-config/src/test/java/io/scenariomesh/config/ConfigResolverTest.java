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
        assertEquals(4, config.workerCount());
        assertEquals(Duration.ofMinutes(2), config.discoveryTimeout());
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
                  workers:
                    count: 7
                    startupTimeout: PT45S
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
        assertEquals(7, config.workerCount());
        assertEquals(Duration.ofSeconds(45), config.workerStartupTimeout());
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
    void resolvesPropertyThenEnvironmentThenYamlThenDefaults() throws Exception {
        Files.writeString(project.resolve("scenariomesh.yml"), """
                scenariomesh:
                  configVersion: 1
                  workers:
                    count: 5
                  execution:
                    adapter: testng
                  logging:
                    liveConsole: false
                """);

        ScenarioMeshConfig config = resolver.resolve(
                project,
                project.resolve("target"),
                Map.of("scenariomesh.workers.count", "9", "scenariomesh.logging.liveConsole", "true"),
                Map.of("SCENARIOMESH_WORKERS_COUNT", "8", "SCENARIOMESH_EXECUTION_ADAPTER", "junit-platform"));

        assertEquals(9, config.workerCount());
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
