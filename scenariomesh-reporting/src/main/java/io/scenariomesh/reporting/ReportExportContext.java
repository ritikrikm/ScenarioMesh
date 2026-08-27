package io.scenariomesh.reporting;

import io.scenariomesh.coordinator.RunOutcome;

import java.nio.file.Path;
import java.util.Objects;

public record ReportExportContext(
        RunOutcome outcome,
        Path reportingDirectory,
        ReportWriter.ReportPaths builtInReports) {
    public ReportExportContext {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reportingDirectory, "reportingDirectory");
        Objects.requireNonNull(builtInReports, "builtInReports");
    }
}
