package io.scenariomesh.coordinator;

import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.core.RuntimePropertyNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @Test
    void freshJvmPerTestClassIsConsumedByCoordinatorAndHiddenFromTargetJvm() {
        RunRequest request = new RunRequest(
                directory,
                List.of(directory),
                List.of(directory),
                Map.of(),
                ScenarioMeshConfig.defaults(directory.resolve("target")),
                DiscoverySelection.all(),
                List.of(),
                Map.of(RuntimePropertyNames.MAVEN_FRESH_JVM_PER_TEST_CLASS, "true"));

        assertTrue(request.freshJvmPerTestClass());
        assertFalse(request.effectiveSystemProperties()
                .containsKey(RuntimePropertyNames.MAVEN_FRESH_JVM_PER_TEST_CLASS));
    }

}
