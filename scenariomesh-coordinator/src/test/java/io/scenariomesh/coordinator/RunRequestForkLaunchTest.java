package io.scenariomesh.coordinator;

import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.core.DiscoverySelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunRequestForkLaunchTest {
    @TempDir Path directory;

    @Test
    void disabledAssertionsOverrideDefaultEaWithLaterDa() {
        RunRequest request = request(false, directory.resolve("work"));

        List<String> args = request.effectiveJvmArgs();
        assertTrue(args.contains("-da"));
        assertFalse(request.enableAssertions());
    }

    @Test
    void configuredWorkingDirectoryBecomesProcessDirectory() {
        Path working = directory.resolve("nested-work");
        RunRequest request = request(true, working);

        assertEquals(working.toAbsolutePath().normalize(), request.projectDirectory());
        assertEquals(directory.toAbsolutePath().normalize(), request.sourceProjectDirectory());
    }

    private RunRequest request(boolean assertions, Path workingDirectory) {
        return new RunRequest(
                directory,
                List.of(directory),
                List.of(directory),
                List.of(directory),
                Map.of(),
                ScenarioMeshConfig.defaults(directory.resolve("target")),
                DiscoverySelection.all(),
                List.of(),
                Map.of(),
                Path.of(System.getProperty("java.home"), "bin", "java"),
                assertions,
                Map.of(),
                Set.of(),
                workingDirectory);
    }
}
