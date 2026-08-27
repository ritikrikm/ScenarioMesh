package io.scenariomesh.coordinator;

import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.core.DiscoverySelection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RunRequest(Path projectDirectory,
                         List<Path> runtimeClasspath,
                         List<Path> testRoots,
                         Map<String,String> userProperties,
                         ScenarioMeshConfig config,
                         DiscoverySelection discoverySelection,
                         List<String> executorJvmArgs,
                         Map<String,String> executorSystemProperties,
                         Path javaExecutable) {
    public RunRequest {
        runtimeClasspath=List.copyOf(runtimeClasspath);
        testRoots=List.copyOf(testRoots);
        userProperties=Map.copyOf(userProperties);
        discoverySelection=discoverySelection==null?DiscoverySelection.all():discoverySelection;
        executorJvmArgs=List.copyOf(executorJvmArgs==null?List.of():executorJvmArgs);
        executorSystemProperties=Map.copyOf(executorSystemProperties==null?Map.of():executorSystemProperties);
        javaExecutable=javaExecutable==null?defaultJavaExecutable():javaExecutable.toAbsolutePath().normalize();
    }

    /** Backward-compatible constructor: use the JVM that launched ScenarioMesh. */
    public RunRequest(Path projectDirectory,
                      List<Path> runtimeClasspath,
                      List<Path> testRoots,
                      Map<String,String> userProperties,
                      ScenarioMeshConfig config,
                      DiscoverySelection discoverySelection,
                      List<String> executorJvmArgs,
                      Map<String,String> executorSystemProperties) {
        this(projectDirectory, runtimeClasspath, testRoots, userProperties, config,
                discoverySelection, executorJvmArgs, executorSystemProperties, null);
    }

    List<String> effectiveJvmArgs(){
        List<String> result=new ArrayList<>(config.workerJvmArgs());
        result.addAll(executorJvmArgs);
        return List.copyOf(result);
    }

    Map<String,String> effectiveSystemProperties(){
        Map<String,String> result=new LinkedHashMap<>(userProperties);
        result.putAll(executorSystemProperties);
        return Map.copyOf(result);
    }

    private static Path defaultJavaExecutable() {
        boolean windows=System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows?"java.exe":"java")
                .toAbsolutePath().normalize();
    }
}
