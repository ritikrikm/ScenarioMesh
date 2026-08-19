package io.scenariomesh.maven;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class DeferredVerificationState {
    private static final String FILE_NAME = "deferred-verification.properties";

    private DeferredVerificationState() {}

    static Path path(Path buildDirectory) {
        return buildDirectory.resolve("scenariomesh").resolve(FILE_NAME);
    }

    static void write(Path buildDirectory,
                      String invocationId,
                      boolean successful,
                      String report,
                      String message) throws IOException {
        Path path = path(buildDirectory);
        Files.createDirectories(path.getParent());
        Properties properties = new Properties();
        properties.setProperty("invocationId", invocationId == null ? "" : invocationId);
        properties.setProperty("successful", Boolean.toString(successful));
        if (report != null) properties.setProperty("report", report);
        if (message != null) properties.setProperty("message", message);
        try (OutputStream output = Files.newOutputStream(path)) {
            properties.store(output, "ScenarioMesh deferred Maven verification state");
        }
    }

    static State read(Path buildDirectory) throws IOException {
        Path path = path(buildDirectory);
        if (!Files.isRegularFile(path)) {
            throw new IOException("ScenarioMesh deferred verification state is missing: " + path);
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return new State(
                properties.getProperty("invocationId", ""),
                Boolean.parseBoolean(properties.getProperty("successful", "false")),
                properties.getProperty("report"),
                properties.getProperty("message"));
    }

    record State(String invocationId, boolean successful, String report, String message) {}
}
