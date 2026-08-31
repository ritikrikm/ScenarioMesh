package io.scenariomesh.reporting;

import io.scenariomesh.coordinator.RunOutcome;
import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.RunId;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.core.RetrySemantics.ExecutionAttempt;
import io.scenariomesh.core.RetrySemantics.LogicalExecution;
import io.scenariomesh.core.RetrySemantics.LogicalStatus;
import io.scenariomesh.core.RetrySemantics.RetryCause;
import io.scenariomesh.core.RetrySemantics.RetryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenRetryReportWriterTest {
    @TempDir Path temp;

    @Test
    void flakyLogicalTestUsesOneTestcaseAndFlakyFailureHistory() throws Exception {
        ScenarioId id = new ScenarioId("flaky");
        ExecutionResult first = result(id, ResultStatus.TEST_FAILURE, "first failure", 30);
        ExecutionResult pass = result(id, ResultStatus.PASSED, null, 10);
        LogicalExecution logical = new LogicalExecution(id, List.of(
                new ExecutionAttempt(id, 0, RetryCause.INITIAL, first),
                new ExecutionAttempt(id, 1, RetryCause.MAVEN_RERUN, pass)),
                LogicalStatus.FLAKY, pass);
        RunOutcome outcome = outcome(List.of(pass), List.of(logical), new RetryPolicy(1, 0));

        ReportWriter.ReportPaths generic = new ReportWriter().write(outcome, temp.resolve("latest"));
        new MavenRetryReportWriter().write(outcome, temp.resolve("latest"), generic);
        String xml = Files.readString(generic.latestJunitXml());

        assertTrue(xml.contains("tests=\"1\""), xml);
        assertTrue(xml.contains("failures=\"0\""), xml);
        assertTrue(xml.contains("<flakyFailure"), xml);
        assertTrue(xml.contains("first failure"), xml);
        assertFalse(xml.contains("<failure type="), xml);
        assertTrue(xml.contains("time=\"0.010\""), "flaky duration must be the successful rerun duration");
        assertTrue(Files.readString(temp.resolve("latest/maven-retry-summary.json")).contains("\"FLAKY\""));
    }

    @Test
    void exhaustedRerunsKeepFirstFailureTopLevelAndSubsequentRerunFailures() throws Exception {
        ScenarioId id = new ScenarioId("always-fails");
        ExecutionResult first = result(id, ResultStatus.TEST_FAILURE, "first failure", 30);
        ExecutionResult second = result(id, ResultStatus.TEST_FAILURE, "second failure", 20);
        LogicalExecution logical = new LogicalExecution(id, List.of(
                new ExecutionAttempt(id, 0, RetryCause.INITIAL, first),
                new ExecutionAttempt(id, 1, RetryCause.MAVEN_RERUN, second)),
                LogicalStatus.FAILED, first);
        RunOutcome outcome = outcome(List.of(first), List.of(logical), new RetryPolicy(1, 0));

        ReportWriter.ReportPaths generic = new ReportWriter().write(outcome, temp.resolve("latest"));
        new MavenRetryReportWriter().write(outcome, temp.resolve("latest"), generic);
        String xml = Files.readString(generic.latestJunitXml());

        assertTrue(xml.contains("tests=\"1\""), xml);
        assertTrue(xml.contains("failures=\"1\""), xml);
        assertTrue(xml.contains("<failure type=\"AssertionError\" message=\"first failure\""), xml);
        assertTrue(xml.contains("<rerunFailure type=\"AssertionError\" message=\"second failure\""), xml);
        assertTrue(xml.contains("time=\"0.030\""), "all-fail duration must remain the first failure duration");
    }

    @Test
    void failOnFlakeThresholdIsVisibleInSidecarAndBuildState() throws Exception {
        ScenarioId id = new ScenarioId("flake-threshold");
        ExecutionResult failure = result(id, ResultStatus.TEST_FAILURE, "boom", 10);
        ExecutionResult pass = result(id, ResultStatus.PASSED, null, 10);
        LogicalExecution logical = new LogicalExecution(id, List.of(
                new ExecutionAttempt(id, 0, RetryCause.INITIAL, failure),
                new ExecutionAttempt(id, 1, RetryCause.MAVEN_RERUN, pass)),
                LogicalStatus.FLAKY, pass);
        RunOutcome outcome = outcome(List.of(pass), List.of(logical), new RetryPolicy(1, 1));

        assertFalse(outcome.successful());
        ReportWriter.ReportPaths generic = new ReportWriter().write(outcome, temp.resolve("latest"));
        new MavenRetryReportWriter().write(outcome, temp.resolve("latest"), generic);
        String json = Files.readString(temp.resolve("latest/maven-retry-summary.json"));
        assertTrue(json.contains("\"flaky\" : 1"), json);
        assertTrue(json.contains("\"failOnFlakeCount\" : 1"), json);
        assertTrue(json.contains("\"buildSuccessful\" : false"), json);
    }

    private RunOutcome outcome(List<ExecutionResult> results, List<LogicalExecution> logical, RetryPolicy policy) throws Exception {
        Path run = temp.resolve("run-" + System.nanoTime());
        Files.createDirectories(run);
        return new RunOutcome(new RunId("run"), List.of("junit-platform"), List.of(), results,
                logical, policy, Duration.ofMillis(100), run);
    }

    private ExecutionResult result(ScenarioId id, ResultStatus status, String message, long millis) {
        Instant started = Instant.parse("2026-08-30T00:00:00Z");
        return new ExecutionResult(id, id.value(), status, Duration.ofMillis(millis), new WorkerId("worker-1"),
                1, started, started.plusMillis(millis), message,
                status == ResultStatus.TEST_FAILURE ? "AssertionError" : null);
    }
}
