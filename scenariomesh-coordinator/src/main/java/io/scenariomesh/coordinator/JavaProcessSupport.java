package io.scenariomesh.coordinator;

import io.scenariomesh.core.RuntimePropertyNames;
import io.scenariomesh.workerruntime.TargetClasspathDescriptor;
import io.scenariomesh.workerruntime.WorkerMain;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class JavaProcessSupport {
    private JavaProcessSupport() {}

    static List<String> command(Path javaExecutable,
                                List<Path> classpath,
                                List<String> jvmArgs,
                                Map<String, String> properties,
                                boolean enableAssertions,
                                String mainClass,
                                List<String> args) {
        List<String> command = new ArrayList<>();
        command.add((javaExecutable == null ? defaultJavaExecutable() : javaExecutable).toString());
        if (enableAssertions) command.add("-ea");
        command.addAll(jvmArgs);
        properties.entrySet().stream()
                .filter(entry -> !RunRequest.INTERNAL_JAVA_EXECUTABLE_PROPERTY.equals(entry.getKey()))
                .filter(entry -> workerBootstrapPropertyAllowed(mainClass, entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> command.add("-D" + entry.getKey() + "=" + entry.getValue()));
        command.add("-cp");
        command.add(classpath.stream()
                .map(Path::toString)
                .reduce((left, right) -> left + File.pathSeparator + right)
                .orElse(""));
        command.add(mainClass);
        command.addAll(args);
        return command;
    }

    private static boolean workerBootstrapPropertyAllowed(String mainClass, String propertyName) {
        if (!WorkerMain.class.getName().equals(mainClass)) return true;
        if (!propertyName.startsWith(RuntimePropertyNames.INTERNAL_PREFIX)) return true;
        // The target classpath is a one-time worker bootstrap handoff. WorkerMain decodes and
        // clears it before adapter discovery/execution, so target tests cannot observe it.
        // Every other ScenarioMesh-internal property remains excluded from the target worker.
        return TargetClasspathDescriptor.SYSTEM_PROPERTY.equals(propertyName);
    }

    static List<String> command(Path javaExecutable,
                                List<Path> classpath,
                                List<String> jvmArgs,
                                Map<String, String> properties,
                                String mainClass,
                                List<String> args) {
        return command(javaExecutable, classpath, jvmArgs, properties, true, mainClass, args);
    }

    static List<String> command(List<Path> classpath,
                                List<String> jvmArgs,
                                Map<String, String> properties,
                                boolean enableAssertions,
                                String mainClass,
                                List<String> args) {
        String selected = properties.get(RunRequest.INTERNAL_JAVA_EXECUTABLE_PROPERTY);
        Path javaExecutable = selected == null || selected.isBlank()
                ? defaultJavaExecutable()
                : Path.of(selected).toAbsolutePath().normalize();
        return command(javaExecutable, classpath, jvmArgs, properties, enableAssertions, mainClass, args);
    }

    static List<String> command(List<Path> classpath,
                                List<String> jvmArgs,
                                Map<String, String> properties,
                                String mainClass,
                                List<String> args) {
        return command(classpath, jvmArgs, properties, true, mainClass, args);
    }

    static void terminateProcessTree(Process process, Duration gracefulWait) {
        if (process == null) return;
        List<ProcessHandle> descendants = process.toHandle().descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .toList();

        descendants.forEach(JavaProcessSupport::destroyQuietly);
        destroyQuietly(process.toHandle());
        waitQuietly(process, gracefulWait);

        descendants.stream().filter(ProcessHandle::isAlive).forEach(JavaProcessSupport::destroyForciblyQuietly);
        if (process.isAlive()) process.destroyForcibly();
        waitQuietly(process, gracefulWait);
    }

    private static void destroyQuietly(ProcessHandle handle) {
        try {
            if (handle.isAlive()) handle.destroy();
        } catch (RuntimeException ignored) {
            // Best-effort cleanup; force-kill path follows.
        }
    }

    private static void destroyForciblyQuietly(ProcessHandle handle) {
        try {
            if (handle.isAlive()) handle.destroyForcibly();
        } catch (RuntimeException ignored) {
            // Caller cannot do more than best effort at process teardown.
        }
    }

    private static void waitQuietly(Process process, Duration wait) {
        try {
            process.waitFor(Math.max(1L, wait.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path defaultJavaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }
}
