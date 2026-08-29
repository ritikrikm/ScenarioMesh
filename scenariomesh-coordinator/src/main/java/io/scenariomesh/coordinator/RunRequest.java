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
    static final String INTERNAL_JAVA_EXECUTABLE_PROPERTY = "scenariomesh.internal.javaExecutable";

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
        // Surefire/Failsafe calculate provider properties from executor configuration first and
        // then promote MavenSession user properties (-D...) later, so user properties win on key
        // collisions. ScenarioMesh must preserve that precedence exactly.
        Map<String,String> result=new LinkedHashMap<>(executorSystemProperties);
        result.putAll(userProperties);
        // The existing WorkerPool launcher still calls JavaProcessSupport's compatibility overload.
        // Carry the selected test JVM as an internal launch hint; JavaProcessSupport consumes and
        // filters this key instead of forwarding it to target tests.
        result.put(INTERNAL_JAVA_EXECUTABLE_PROPERTY, javaExecutable.toString());
        return Map.copyOf(result);
    }

    private static Path defaultJavaExecutable() {
        boolean windows=System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows?"java.exe":"java")
                .toAbsolutePath().normalize();
    }
}
