package io.scenariomesh.coordinator;

import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.workerruntime.TargetClasspathDescriptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record RunRequest(Path projectDirectory,
                         List<Path> runtimeClasspath,
                         List<Path> controlClasspath,
                         List<Path> testRoots,
                         Map<String,String> userProperties,
                         ScenarioMeshConfig config,
                         DiscoverySelection discoverySelection,
                         List<String> executorJvmArgs,
                         Map<String,String> executorSystemProperties,
                         Path javaExecutable,
                         boolean enableAssertions,
                         Map<String,String> executorEnvironmentVariables,
                         Set<String> excludedEnvironmentVariables,
                         Path executorWorkingDirectory) {
    static final String INTERNAL_JAVA_EXECUTABLE_PROPERTY = "scenariomesh.internal.javaExecutable";

    public RunRequest {
        projectDirectory=projectDirectory.toAbsolutePath().normalize();
        runtimeClasspath=List.copyOf(runtimeClasspath);
        controlClasspath=List.copyOf(controlClasspath==null||controlClasspath.isEmpty()?runtimeClasspath:controlClasspath);
        testRoots=List.copyOf(testRoots);
        userProperties=Map.copyOf(userProperties);
        discoverySelection=discoverySelection==null?DiscoverySelection.all():discoverySelection;
        executorJvmArgs=List.copyOf(executorJvmArgs==null?List.of():executorJvmArgs);
        executorSystemProperties=Map.copyOf(executorSystemProperties==null?Map.of():executorSystemProperties);
        javaExecutable=javaExecutable==null?defaultJavaExecutable():javaExecutable.toAbsolutePath().normalize();
        executorEnvironmentVariables=Map.copyOf(
                executorEnvironmentVariables==null?Map.of():executorEnvironmentVariables);
        excludedEnvironmentVariables=Set.copyOf(
                excludedEnvironmentVariables==null?Set.of():new LinkedHashSet<>(excludedEnvironmentVariables));
        executorWorkingDirectory=(executorWorkingDirectory==null?projectDirectory:executorWorkingDirectory)
                .toAbsolutePath().normalize();
    }

    /** Backward-compatible constructor used by integrations that still provide one mixed classpath. */
    public RunRequest(Path projectDirectory,
                      List<Path> runtimeClasspath,
                      List<Path> testRoots,
                      Map<String,String> userProperties,
                      ScenarioMeshConfig config,
                      DiscoverySelection discoverySelection,
                      List<String> executorJvmArgs,
                      Map<String,String> executorSystemProperties,
                      Path javaExecutable) {
        this(projectDirectory, runtimeClasspath, runtimeClasspath, testRoots, userProperties, config,
                discoverySelection, executorJvmArgs, executorSystemProperties, javaExecutable,
                true, Map.of(), Set.of(), projectDirectory);
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
        this(projectDirectory, runtimeClasspath, runtimeClasspath, testRoots, userProperties, config,
                discoverySelection, executorJvmArgs, executorSystemProperties, null,
                true, Map.of(), Set.of(), projectDirectory);
    }

    /** Control-plane subprocesses launch from this classpath. */
    @Override public List<Path> runtimeClasspath() { return controlClasspath; }

    /** Target tests and framework adapters resolve from this child execution realm. */
    public List<Path> targetRuntimeClasspath() { return this.runtimeClasspath; }

    List<String> effectiveJvmArgs(){
        List<String> result=new ArrayList<>(config.workerJvmArgs());
        result.addAll(executorJvmArgs);
        return List.copyOf(result);
    }

    Map<String,String> effectiveSystemProperties(){
        Map<String,String> result=new LinkedHashMap<>(executorSystemProperties);
        result.putAll(userProperties);
        result.put(INTERNAL_JAVA_EXECUTABLE_PROPERTY, javaExecutable.toString());
        result.put(TargetClasspathDescriptor.SYSTEM_PROPERTY,
                TargetClasspathDescriptor.encodeInline(targetRuntimeClasspath()));
        return Map.copyOf(result);
    }

    private static Path defaultJavaExecutable() {
        boolean windows=System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows?"java.exe":"java")
                .toAbsolutePath().normalize();
    }
}
