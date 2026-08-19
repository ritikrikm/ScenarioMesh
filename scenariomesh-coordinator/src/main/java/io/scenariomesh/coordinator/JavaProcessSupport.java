package io.scenariomesh.coordinator;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class JavaProcessSupport {
    private JavaProcessSupport() {}

    static List<String> command(List<Path> classpath,
                                List<String> jvmArgs,
                                Map<String, String> properties,
                                String mainClass,
                                List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        // Maven Surefire enables assertions by default. ScenarioMesh child JVMs
        // mirror that default unless a future compatibility layer explicitly
        // reproduces a target project's enableAssertions override.
        command.add("-ea");
        command.addAll(jvmArgs);
        properties.entrySet().stream()
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

    private static Path javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }
}
