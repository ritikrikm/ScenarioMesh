package io.scenariomesh.reporting;

import java.util.List;
import java.util.Map;

public final class TestReportArtifactProvider implements ReportArtifactProvider {
    @Override
    public String id() {
        return "test-artifacts";
    }

    @Override
    public List<ReportArtifact> artifacts(ReportExportContext context) {
        return List.of(
                new ReportArtifact("screenshot-1", "scenario-1", "screenshot", "Failure screenshot",
                        "artifacts/failure.png", "image/png", Map.of("source", "fixture")),
                new ReportArtifact("trace-1", "scenario-1", "trace", "External trace",
                        "https://example.test/traces/1", "text/html", Map.of()));
    }
}
