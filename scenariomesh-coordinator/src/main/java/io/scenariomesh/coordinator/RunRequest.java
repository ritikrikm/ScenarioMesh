package io.scenariomesh.coordinator;

import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.core.DiscoverySelection;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record RunRequest(Path projectDirectory,
                         List<Path> runtimeClasspath,
                         List<Path> testRoots,
                         Map<String,String> userProperties,
                         ScenarioMeshConfig config,
                         DiscoverySelection discoverySelection) {
    public RunRequest {
        runtimeClasspath=List.copyOf(runtimeClasspath);
        testRoots=List.copyOf(testRoots);
        userProperties=Map.copyOf(userProperties);
        discoverySelection=discoverySelection==null?DiscoverySelection.all():discoverySelection;
    }
}
