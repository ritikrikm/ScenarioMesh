package io.scenariomesh.coordinator;

import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.core.DiscoverySelection;\nimport io.scenariomesh.core.RuntimePropertyNames;
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
    void freshClassJvmKeepsMavenArgLineOutOfSupervisor() {
        RunRequest request = new RunRequest(
                directory,
                List.of(directory),
                List.of(directory),
                List.of(directory),
                Map.of(),
                ScenarioMeshConfig.defaults(directory.resolve("target")),
                DiscoverySelection.all(),
                List.of("-Xmx25M", "-Dtarget.flag=true"),
                Map.of(RuntimePropertyNames.MAVEN_FRESH_JVM_PER_TEST_CLASS, "true"),
                Path.of(System.getProperty("java.home"), "bin", "java"),
                true,
                Map.of(),
                Set.of(),
                directory.resolve("work"));

        assertTrue(request.effectiveJvmArgs().contains("-Xmx25M"));
        assertTrue(request.effectiveJvmArgs().contains("-Dtarget.flag=true"));
        assertFalse(request.controlJvmArgs().contains("-Xmx25M"));
        assertFalse(request.controlJvmArgs().contains("-Dtarget.flag=true"));
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
