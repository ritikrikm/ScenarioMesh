package io.scenariomesh.workerruntime;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Immutable launch contract for a Maven-compatible class-scoped target JVM.
 *
 * <p>The ScenarioMesh supervisor is intentionally not launched with these JVM arguments. This
 * keeps coordinator/protocol memory outside target constraints such as Surefire {@code -Xmx}.
 * The fresh child process receives the exact target JVM arguments and properties instead.</p>
 */
public record ForkedJvmLaunch(
        String javaExecutable,
        List<String> jvmArgs,
        Map<String, String> systemProperties,
        List<String> targetClasspath) implements Serializable {

    public ForkedJvmLaunch {
        if (javaExecutable == null || javaExecutable.isBlank()) {
            throw new IllegalArgumentException("target java executable is required");
        }
        jvmArgs = List.copyOf(jvmArgs == null ? List.of() : jvmArgs);
        systemProperties = Map.copyOf(systemProperties == null ? Map.of() : systemProperties);
        targetClasspath = List.copyOf(targetClasspath == null ? List.of() : targetClasspath);
        if (targetClasspath.isEmpty()) throw new IllegalArgumentException("target classpath is required");
    }

    public static void write(Path file, ForkedJvmLaunch launch) throws Exception {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(file))) {
            output.writeObject(launch);
        }
    }

    public static ForkedJvmLaunch read(Path file) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(file))) {
            Object value = input.readObject();
            if (!(value instanceof ForkedJvmLaunch launch)) {
                throw new IllegalArgumentException("invalid forked JVM launch descriptor");
            }
            return launch;
        }
    }
}
