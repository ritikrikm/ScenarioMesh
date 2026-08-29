package io.scenariomesh.coordinator;

import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.core.DiscoverySelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunRequestPropertyPrecedenceTest {
    @TempDir Path directory;

    @Test
    void mavenUserPropertyWinsOverExecutorSystemPropertyVariable() {
        RunRequest request = new RunRequest(
                directory,
                List.of(directory),
                List.of(directory),
                Map.of("example.property", "from-cli"),
                ScenarioMeshConfig.defaults(directory.resolve("target")),
                DiscoverySelection.all(),
                List.of(),
                Map.of("example.property", "from-pom"));

        assertEquals("from-cli", request.effectiveSystemProperties().get("example.property"));
    }
}
