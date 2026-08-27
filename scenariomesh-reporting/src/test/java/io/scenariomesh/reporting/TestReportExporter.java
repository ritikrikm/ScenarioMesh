package io.scenariomesh.reporting;

import java.nio.file.Files;

public final class TestReportExporter implements ReportExporter {
    @Override
    public String id() {
        return "test-exporter";
    }

    @Override
    public void export(ReportExportContext context) throws Exception {
        Files.writeString(context.reportingDirectory().resolve("test-exporter.marker"),
                context.outcome().runId().value() + "\n" + context.builtInReports().latestJunitXml());
    }
}
