package io.scenariomesh.workerruntime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForkedJvmLaunchTest {
    @TempDir Path directory;

    @Test
    void roundTripsExactTargetLaunchContract() throws Exception {
        ForkedJvmLaunch expected = new ForkedJvmLaunch(
                "/opt/test-jdk/bin/java",
                List.of("-Xmx25M", "-XX:+EnableDynamicAgentLoading"),
                Map.of("target.mode", "strict"),
                List.of("/tmp/test-classes", "/tmp/dependency.jar"));

        Path file = directory.resolve("launch.bin");
        ForkedJvmLaunch.write(file, expected);

        assertEquals(expected, ForkedJvmLaunch.read(file));
    }
}
