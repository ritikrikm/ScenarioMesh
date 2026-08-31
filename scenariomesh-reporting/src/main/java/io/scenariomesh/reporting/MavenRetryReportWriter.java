package io.scenariomesh.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.coordinator.RunOutcome;
import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.RetrySemantics.ExecutionAttempt;
import io.scenariomesh.core.RetrySemantics.LogicalExecution;
import io.scenariomesh.core.RetrySemantics.LogicalStatus;
import io.scenariomesh.workerruntime.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Re-materializes the JUnit XML surface with Surefire/Failsafe retry vocabulary and writes a
 * lossless ScenarioMesh logical-attempt sidecar. The legacy ReportWriter stays the generic
 * reporting surface; Maven takeover opts into this stricter executor-compatible representation.
 */
public final class MavenRetryReportWriter {
    public RetryReportPaths write(RunOutcome outcome, Path reportingDirectory,
                                  ReportWriter.ReportPaths genericPaths) throws Exception {
        List<LogicalExecution> logical = new ArrayList<>(outcome.logicalExecutions());
        logical.sort(Comparator.comparing(item -> item.logicalTask().value()));

        Files.createDirectories(reportingDirectory);
        createParent(genericPaths.junitXml());
        createParent(genericPaths.latestJunitXml());
        Files.createDirectories(outcome.runDirectory());

        String xml = junitXml(outcome, logical);
        Files.writeString(genericPaths.junitXml(), xml, StandardCharsets.UTF_8);
        Files.writeString(genericPaths.latestJunitXml(), xml, StandardCharsets.UTF_8);

        Path runJson = outcome.runDirectory().resolve("maven-retry-summary.json");
        Path latestJson = reportingDirectory.resolve("maven-retry-summary.json");
        RetrySummary summary = RetrySummary.from(outcome, logical);
        ObjectMapper mapper = JsonCodec.create();
        mapper.writerWithDefaultPrettyPrinter().writeValue(runJson.toFile(), summary);
        Files.copy(runJson, latestJson, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new RetryReportPaths(genericPaths.junitXml(), genericPaths.latestJunitXml(), runJson, latestJson);
    }

    private void createParent(Path path) throws Exception {
        Path parent = path == null ? null : path.getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    private String junitXml(RunOutcome outcome, List<LogicalExecution> logical) {
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        for (LogicalExecution execution : logical) {
            if (execution.status() == LogicalStatus.FAILED) failures++;
            else if (execution.status() == LogicalStatus.INFRASTRUCTURE_FAILED) errors++;
            else if (execution.status() == LogicalStatus.SKIPPED) skipped++;
        }

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"ScenarioMesh\" tests=\"").append(logical.size())
                .append("\" failures=\"").append(failures)
                .append("\" errors=\"").append(errors)
                .append("\" skipped=\"").append(skipped)
                .append("\" time=\"").append(seconds(outcome.duration().toMillis())).append("\">\n");
        for (LogicalExecution execution : logical) appendTestCase(xml, execution);
        xml.append("</testsuite>\n");
        return xml.toString();
    }

    private void appendTestCase(StringBuilder xml, LogicalExecution logical) {
        ExecutionResult canonical = logical.canonicalResult();
        xml.append("  <testcase name=\"").append(xmlEscape(canonical.displayName()))
                .append("\" classname=\"").append(xmlEscape(canonical.workerId().value()))
                .append("\" time=\"").append(seconds(canonical.duration().toMillis())).append("\">\n");

        if (logical.status() == LogicalStatus.FLAKY) {
            for (ExecutionAttempt attempt : logical.attempts()) {
                if (attempt.result().status() == ResultStatus.TEST_FAILURE) {
                    appendAttemptFailure(xml, "flakyFailure", attempt);
                }
            }
        } else if (logical.status() == LogicalStatus.FAILED) {
            appendFailure(xml, "failure", logical.attempts().get(0).result());
            for (int index = 1; index < logical.attempts().size(); index++) {
                ExecutionAttempt attempt = logical.attempts().get(index);
                if (attempt.result().status() == ResultStatus.TEST_FAILURE) {
                    appendAttemptFailure(xml, "rerunFailure", attempt);
                }
            }
        } else if (logical.status() == LogicalStatus.SKIPPED) {
            xml.append("    <skipped message=\"")
                    .append(xmlEscape(nullSafe(canonical.failureMessage()))).append("\"/>\n");
        } else if (logical.status() == LogicalStatus.INFRASTRUCTURE_FAILED) {
            appendFailure(xml, "error", canonical);
            appendPriorTestFailuresAsDiagnostics(xml, logical);
        }
        xml.append("  </testcase>\n");
    }

    private void appendPriorTestFailuresAsDiagnostics(StringBuilder xml, LogicalExecution logical) {
        for (ExecutionAttempt attempt : logical.attempts()) {
            if (attempt.result() == logical.canonicalResult()) continue;
            if (attempt.result().status() == ResultStatus.TEST_FAILURE) {
                xml.append("    <system-out>")
                        .append(xmlEscape("ScenarioMesh prior Maven test failure before infrastructure termination; rerunIndex="
                                + attempt.rerunIndex() + "; type=" + nullSafe(attempt.result().failureType())
                                + "; message=" + nullSafe(attempt.result().failureMessage())))
                        .append("</system-out>\n");
            }
        }
    }

    private void appendAttemptFailure(StringBuilder xml, String element, ExecutionAttempt attempt) {
        ExecutionResult result = attempt.result();
        xml.append("    <").append(element)
                .append(" type=\"").append(xmlEscape(nullSafe(result.failureType())))
                .append("\" message=\"").append(xmlEscape(nullSafe(result.failureMessage())))
                .append("\">")
                .append(xmlEscape("ScenarioMesh Maven rerun index " + attempt.rerunIndex()
                        + ", infrastructureAttempt=" + result.attempt()))
                .append("</").append(element).append(">\n");
    }

    private void appendFailure(StringBuilder xml, String element, ExecutionResult result) {
        xml.append("    <").append(element)
                .append(" type=\"").append(xmlEscape(nullSafe(result.failureType())))
                .append("\" message=\"").append(xmlEscape(nullSafe(result.failureMessage())))
                .append("\"/>\n");
    }

    private String seconds(long millis) {
        return String.format(Locale.ROOT, "%.3f", Math.max(0L, millis) / 1000.0d);
    }

    private String xmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String nullSafe(String value) { return value == null ? "" : value; }

    public record RetryReportPaths(Path junitXml, Path latestJunitXml, Path json, Path latestJson) {}

    public record RetrySummary(String runId,
                               int logicalTests,
                               int flaky,
                               int failed,
                               int infrastructureFailed,
                               int rerunFailingTestsCount,
                               int failOnFlakeCount,
                               boolean buildSuccessful,
                               List<LogicalExecution> executions) {
        static RetrySummary from(RunOutcome outcome, List<LogicalExecution> logical) {
            int flaky = 0;
            int failed = 0;
            int infrastructure = 0;
            for (LogicalExecution execution : logical) {
                if (execution.status() == LogicalStatus.FLAKY) flaky++;
                else if (execution.status() == LogicalStatus.FAILED) failed++;
                else if (execution.status() == LogicalStatus.INFRASTRUCTURE_FAILED) infrastructure++;
            }
            return new RetrySummary(outcome.runId().value(), logical.size(), flaky, failed, infrastructure,
                    outcome.retryPolicy().rerunFailingTestsCount(), outcome.retryPolicy().failOnFlakeCount(),
                    outcome.successful(), List.copyOf(logical));
        }
    }
}
