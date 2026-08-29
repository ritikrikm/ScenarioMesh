package io.scenariomesh.maven.extension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads Surefire/Failsafe selector files only when ScenarioMesh can reproduce every entry exactly. */
final class ExternalSelectionFile {
    private ExternalSelectionFile() {}

    static Analysis read(Path projectBaseDir, String configuredPath, String parameterName) {
        if (projectBaseDir == null) {
            return Analysis.unsupported(parameterName + " cannot be reproduced because the Maven project base directory is unavailable");
        }
        if (configuredPath == null || configuredPath.isBlank()) {
            return Analysis.unsupported(parameterName + " is blank; ScenarioMesh will not guess Maven file binding semantics");
        }

        Path path;
        try {
            Path configured = Path.of(configuredPath.trim());
            path = configured.isAbsolute() ? configured.normalize() : projectBaseDir.resolve(configured).normalize();
        } catch (RuntimeException invalidPath) {
            return Analysis.unsupported(parameterName + " path is invalid: " + invalidPath.getMessage());
        }

        if (!Files.isRegularFile(path)) {
            return Analysis.unsupported(parameterName + " does not resolve to a readable regular file: " + path);
        }

        List<String> patterns = new ArrayList<>();
        try {
            for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                patterns.add(line);
            }
        } catch (IOException unreadable) {
            return Analysis.unsupported(parameterName + " could not be read: " + unreadable.getMessage());
        }

        MavenClassNamePatterns.SelectionAnalysis parsed = MavenClassNamePatterns.analyze(patterns);
        if (!parsed.supported()) {
            return Analysis.unsupported(parameterName + " contains selectors outside ScenarioMesh's proven class-only subset: "
                    + String.join("; ", parsed.unsupportedReasons()));
        }
        return Analysis.supported(patterns);
    }

    record Analysis(boolean supported, List<String> patterns, String reason) {
        Analysis {
            patterns = List.copyOf(patterns == null ? List.of() : patterns);
        }

        static Analysis supported(List<String> patterns) {
            return new Analysis(true, patterns, null);
        }

        static Analysis unsupported(String reason) {
            return new Analysis(false, List.of(), reason);
        }
    }
}
