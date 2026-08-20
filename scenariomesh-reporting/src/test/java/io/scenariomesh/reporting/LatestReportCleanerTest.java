package io.scenariomesh.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatestReportCleanerTest {
    @TempDir
    Path temp;

    @Test
    void clearsOnlyMutableLatestAliasesAndKeepsHistoricalRuns() throws Exception {
        Path runs = Files.createDirectories(temp.resolve("runs/run-123"));
        Path historical = Files.writeString(runs.resolve("report.html"), "history");
        Path latestHtml = Files.writeString(temp.resolve("report.html"), "old latest");
        Path latestJson = Files.writeString(temp.resolve("summary.json"), "old latest");
        Path latestJunit = Files.writeString(temp.resolve("junit.xml"), "old latest");
        Path unrelated = Files.writeString(temp.resolve("worker-note.txt"), "keep");

        new LatestReportCleaner().clear(temp);

        assertFalse(Files.exists(latestHtml));
        assertFalse(Files.exists(latestJson));
        assertFalse(Files.exists(latestJunit));
        assertTrue(Files.exists(historical));
        assertTrue(Files.exists(unrelated));
    }
}
