package io.scenariomesh.reporting;

import io.scenariomesh.coordinator.RunOutcome;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record ReportExportContext(
        RunOutcome outcome,
        Path reportingDirectory,
        ReportWriter.ReportPaths builtInReports,
        List<ReportArtifact> artifacts) {

    /** Backward-compatible constructor for exporters compiled before artifact references existed. */
    public ReportExportContext(RunOutcome outcome, Path reportingDirectory,
                               ReportWriter.ReportPaths builtInReports) {
        this(outcome, reportingDirectory, builtInReports, List.of());
    }

    public ReportExportContext {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reportingDirectory, "reportingDirectory");
        Objects.requireNonNull(builtInReports, "builtInReports");
        artifacts = List.copyOf(artifacts == null ? List.of() : artifacts);
    }
}
