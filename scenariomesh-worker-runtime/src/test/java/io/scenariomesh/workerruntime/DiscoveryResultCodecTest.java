package io.scenariomesh.workerruntime;

import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscoveryResultCodecTest {
    @Test
    void roundTripsWithoutAnyJsonLibraryContract() throws Exception {
        ScenarioTask task = new ScenarioTask(
                new ScenarioId("stable-id"),
                "example test",
                "junit-platform",
                "junit-platform",
                URI.create("file:///example/Test.java"),
                12,
                "[engine:junit-jupiter]/[class:example.Test]/[method:works()]",
                Set.of("fast"),
                Map.of("executionScopeId", "scope-1"));
        DiscoveryMain.DiscoveryResult expected = new DiscoveryMain.DiscoveryResult(
                List.of("junit-platform"),
                List.of(new DiscoveryMain.AdapterEvidence("junit-platform", "junit-platform", true, 1, null)),
                List.of(),
                List.of(task));

        var output = Files.createTempFile("scenariomesh-discovery", ".bin");
        DiscoveryResultCodec.write(output, expected);

        assertEquals(expected, DiscoveryResultCodec.read(output));
    }
}
