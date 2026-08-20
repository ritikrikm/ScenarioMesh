package io.scenariomesh.reporting;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Removes only mutable "latest" aliases before a new owned execution begins.
 * Historical run artifacts under {@code runs/<runId>} are intentionally retained.
 */
public final class LatestReportCleaner {
    private static final String[] LATEST_FILES = {"summary.json", "junit.xml", "report.html"};

    public void clear(Path reportingDirectory) throws Exception {
        if (reportingDirectory == null) {
            return;
        }
        for (String name : LATEST_FILES) {
            Files.deleteIfExists(reportingDirectory.resolve(name));
        }
    }
}
