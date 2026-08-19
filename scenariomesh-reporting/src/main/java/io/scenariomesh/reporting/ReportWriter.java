package io.scenariomesh.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.coordinator.RunOutcome;
import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.workerruntime.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ReportWriter {
    public ReportPaths write(RunOutcome outcome, Path reportingDirectory) throws Exception {
        Files.createDirectories(outcome.runDirectory());
        List<ExecutionResult> ordered = new ArrayList<>(outcome.results());
        ordered.sort(Comparator.comparing(ExecutionResult::displayName).thenComparing(result -> result.scenarioId().value()));
        Summary summary = Summary.from(outcome, ordered);
        Path json = outcome.runDirectory().resolve("summary.json");
        Path junit = outcome.runDirectory().resolve("junit.xml");
        Path html = outcome.runDirectory().resolve("report.html");
        ObjectMapper mapper = JsonCodec.create();
        mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), summary);
        Files.writeString(junit, junitXml(summary), StandardCharsets.UTF_8);
        Files.writeString(html, html(summary), StandardCharsets.UTF_8);
        Files.createDirectories(reportingDirectory);
        Path latestJson = reportingDirectory.resolve("summary.json");
        Path latestJunit = reportingDirectory.resolve("junit.xml");
        Path latestHtml = reportingDirectory.resolve("report.html");
        Files.copy(json, latestJson, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(junit, latestJunit, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(html, latestHtml, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new ReportPaths(json, junit, html, latestJson, latestJunit, latestHtml);
    }

    private String junitXml(Summary summary) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"ScenarioMesh\" tests=\"").append(summary.total())
                .append("\" failures=\"").append(summary.failed())
                .append("\" errors=\"").append(summary.infrastructureFailed())
                .append("\" time=\"").append(seconds(summary.durationMillis())).append("\">\n");
        for (ExecutionResult result : summary.results()) {
            xml.append("  <testcase name=\"").append(xmlEscape(result.displayName()))
                    .append("\" classname=\"").append(xmlEscape(result.workerId().value()))
                    .append("\" time=\"").append(seconds(result.duration().toMillis())).append("\">\n");
            if (result.status() == ResultStatus.TEST_FAILURE) {
                xml.append("    <failure type=\"").append(xmlEscape(nullSafe(result.failureType())))
                        .append("\" message=\"").append(xmlEscape(nullSafe(result.failureMessage()))).append("\"/>\n");
            } else if (result.status() != ResultStatus.PASSED) {
                xml.append("    <error type=\"").append(xmlEscape(nullSafe(result.failureType())))
                        .append("\" message=\"").append(xmlEscape(nullSafe(result.failureMessage()))).append("\"/>\n");
            }
            xml.append("  </testcase>\n");
        }
        xml.append("</testsuite>\n");
        return xml.toString();
    }

    private String html(Summary summary) {
        StringBuilder body = new StringBuilder();
        body.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>ScenarioMesh Report</title>")
                .append("<style>body{font-family:system-ui,sans-serif;max-width:1200px;margin:40px auto;padding:0 20px}table{border-collapse:collapse;width:100%}th,td{border:1px solid #ddd;padding:8px;text-align:left}th{background:#f5f5f5}code{font-size:.9em}</style></head><body>")
                .append("<h1>ScenarioMesh</h1>")
                .append("<p>Run <code>").append(htmlEscape(summary.runId())).append("</code> · ")
                .append(summary.total()).append(" tests · ").append(summary.passed()).append(" passed · ")
                .append(summary.failed()).append(" failed · ").append(summary.infrastructureFailed()).append(" infrastructure</p>")
                .append("<p>Adapters: ").append(htmlEscape(String.join(", ", summary.adapters()))).append("</p>")
                .append("<table><thead><tr><th>Status</th><th>Test</th><th>Worker</th><th>Duration</th><th>Failure</th></tr></thead><tbody>");
        for (ExecutionResult result : summary.results()) {
            body.append("<tr><td>").append(htmlEscape(result.status().name())).append("</td><td>")
                    .append(htmlEscape(result.displayName())).append("</td><td>")
                    .append(htmlEscape(result.workerId().value())).append("</td><td>")
                    .append(result.duration().toMillis()).append(" ms</td><td>")
                    .append(htmlEscape(nullSafe(result.failureMessage()))).append("</td></tr>");
        }
        body.append("</tbody></table></body></html>");
        return body.toString();
    }

    private String seconds(long millis) { return String.format(java.util.Locale.ROOT, "%.3f", millis / 1000.0d); }
    private String xmlEscape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
    private String htmlEscape(String value) { return xmlEscape(value); }
    private String nullSafe(String value) { return value == null ? "" : value; }

    public record ReportPaths(Path json, Path junitXml, Path html, Path latestJson, Path latestJunitXml, Path latestHtml) {}

    public record Summary(String runId, List<String> adapters, int total, int passed, int failed, int infrastructureFailed, long durationMillis, List<ExecutionResult> results) {
        static Summary from(RunOutcome outcome, List<ExecutionResult> results) {
            int passed = 0, failed = 0, infrastructure = 0;
            for (ExecutionResult result : results) {
                if (result.status() == ResultStatus.PASSED) passed++;
                else if (result.status() == ResultStatus.TEST_FAILURE) failed++;
                else infrastructure++;
            }
            return new Summary(outcome.runId().value(), outcome.adapters(), results.size(), passed, failed, infrastructure, outcome.duration().toMillis(), List.copyOf(results));
        }
    }
}
