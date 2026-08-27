package io.scenariomesh.config;

import io.scenariomesh.config.ScenarioMeshConfig.SchedulingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchedulingConfigTest {
    @TempDir
    Path project;

    @Test
    void defaultsToHistoryAwareLpt() {
        ScenarioMeshConfig config = new ConfigResolver().resolve(project, project.resolve("target"), Map.of(), Map.of());
        assertEquals(SchedulingMode.HISTORY_LPT, config.schedulingMode());
    }

    @Test
    void propertyOverridesEnvironmentAndYaml() throws Exception {
        Files.writeString(project.resolve("scenariomesh.yml"), """
                scenariomesh:
                  configVersion: 1
                  scheduling:
                    strategy: history-lpt
                """);
        ScenarioMeshConfig config = new ConfigResolver().resolve(project, project.resolve("target"),
                Map.of("scenariomesh.scheduling.strategy", "fifo"),
                Map.of("SCENARIOMESH_SCHEDULING_STRATEGY", "history-lpt"));
        assertEquals(SchedulingMode.FIFO, config.schedulingMode());
    }

    @Test
    void environmentCanSelectStrictFifo() {
        ScenarioMeshConfig config = new ConfigResolver().resolve(project, project.resolve("target"), Map.of(),
                Map.of("SCENARIOMESH_SCHEDULING_STRATEGY", "fifo"));
        assertEquals(SchedulingMode.FIFO, config.schedulingMode());
    }

    @Test
    void rejectsUnknownStrategy() {
        assertThrows(IllegalArgumentException.class, () -> new ConfigResolver().resolve(
                project, project.resolve("target"),
                Map.of("scenariomesh.scheduling.strategy", "random"), Map.of()));
    }
}
