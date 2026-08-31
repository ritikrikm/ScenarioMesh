package io.scenariomesh.workerruntime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreflightProbeResultTest {
    @TempDir Path directory;

    @Test
    void roundTripsWithoutAnyJsonDependency() throws Exception {
        Path output = directory.resolve("probe.properties");
        PreflightProbeMain.ProbeResult expected = new PreflightProbeMain.ProbeResult(
                "OWNABLE",
                "JUnit Platform + Cucumber",
                Set.of("junit-platform", "cucumber-junit4"),
                Set.of("junit-jupiter", "cucumber"),
                "runtime-fingerprint");

        PreflightProbeMain.writeResult(output, expected);

        assertEquals(expected, PreflightProbeMain.readResult(output));
    }
}
