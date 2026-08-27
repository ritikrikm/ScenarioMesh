package io.scenariomesh.reporting;

import io.scenariomesh.coordinator.RunOutcome;
import io.scenariomesh.core.Domain.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportExportersTest {
    @TempDir
    Path directory;

    @Test
    void serviceLoadedArtifactsAreManifestedAndVisibleToExporters() throws Exception {
        Path latestJson = directory.resolve("summary.json");
        Path latestJunit = directory.resolve("junit.xml");
        Path latestHtml = directory.resolve("report.html");
        Files.writeString(latestJson, "{}");
        Files.writeString(latestJunit, "<testsuite/>");
        Files.writeString(latestHtml, "<html/>");
        RunOutcome outcome = new RunOutcome(new RunId("run-p6"), List.of("junit-platform"),
                List.of(), List.of(), Duration.ZERO, directory.resolve("runs/run-p6"));
        ReportWriter.ReportPaths paths = new ReportWriter.ReportPaths(
                directory.resolve("runs/run-p6/summary.json"),
                directory.resolve("runs/run-p6/junit.xml"),
                directory.resolve("runs/run-p6/report.html"),
                latestJson, latestJunit, latestHtml);

        ReportExporters.export(outcome, directory, paths);

        Path marker = directory.resolve("test-exporter.marker");
        assertTrue(Files.isRegularFile(marker));
        String content = Files.readString(marker);
        assertTrue(content.contains("run-p6"));
        assertTrue(content.contains("junit.xml"));
        assertTrue(content.contains("artifacts=2"));

        Path manifest = directory.resolve("artifacts.json");
        assertTrue(Files.isRegularFile(manifest));
        String json = Files.readString(manifest);
        assertTrue(json.contains("\"version\" : 1"));
        assertTrue(json.contains("failure.png"));
        assertTrue(json.contains("https://example.test/traces/1"));
    }

    @Test
    void artifactLocationRejectsTraversalAndNonHttpsExternalUris() {
        assertThrows(IllegalArgumentException.class, () -> new ReportArtifact(
                "bad-1", null, "log", "bad", "../secret.log", "text/plain", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ReportArtifact(
                "bad-2", null, "trace", "bad", "http://example.test/trace", "text/html", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ReportArtifact(
                "bad-3", null, "file", "bad", "/tmp/secret", null, Map.of()));
    }
}
