package io.scenariomesh.coordinator;

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
                                String mainClass,
                                List<String> args) {
        List<String> command = new ArrayList<>();
        command.add((javaExecutable == null ? defaultJavaExecutable() : javaExecutable).toString());
        // Maven Surefire enables assertions by default. ScenarioMesh child JVMs
        // mirror that default unless a future compatibility layer explicitly
        // reproduces a target project's enableAssertions override.
        command.add("-ea");
        command.addAll(jvmArgs);
        properties.entrySet().stream()
                .filter(entry -> !RunRequest.INTERNAL_JAVA_EXECUTABLE_PROPERTY.equals(entry.getKey()))
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

    static List<String> command(List<Path> classpath,
                                List<String> jvmArgs,
                                Map<String, String> properties,
                                String mainClass,
                                List<String> args) {
        String selected = properties.get(RunRequest.INTERNAL_JAVA_EXECUTABLE_PROPERTY);
        Path javaExecutable = selected == null || selected.isBlank()
                ? defaultJavaExecutable()
                : Path.of(selected).toAbsolutePath().normalize();
        return command(javaExecutable, classpath, jvmArgs, properties, mainClass, args);
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
