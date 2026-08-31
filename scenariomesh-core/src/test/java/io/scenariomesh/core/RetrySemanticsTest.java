package io.scenariomesh.core;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.core.RetrySemantics.ExecutionAttempt;
import io.scenariomesh.core.RetrySemantics.LogicalExecution;
import io.scenariomesh.core.RetrySemantics.LogicalStatus;
import io.scenariomesh.core.RetrySemantics.RetryCause;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetrySemanticsTest {
    private static final ScenarioId ID = new ScenarioId("logical-test");

    @Test
    void laterPassIsOneFlakyLogicalExecution() {
        ExecutionResult firstFailure = result(ResultStatus.TEST_FAILURE, 3);
        ExecutionResult rerunPass = result(ResultStatus.PASSED, 1);

        LogicalExecution logical = RetrySemantics.aggregate(List.of(
                new ExecutionAttempt(ID, 0, RetryCause.INITIAL, firstFailure),
                new ExecutionAttempt(ID, 1, RetryCause.MAVEN_RERUN, rerunPass)));

        assertEquals(LogicalStatus.FLAKY, logical.status());
        assertEquals(2, logical.attempts().size());
        assertEquals(rerunPass, logical.canonicalResult());
        // Infrastructure attempt 3 is preserved independently from Maven rerun index 0.
        assertEquals(3, logical.attempts().get(0).result().attempt());
    }

    @Test
    void exhaustedRerunsKeepFirstFailureCanonical() {
        ExecutionResult firstFailure = result(ResultStatus.TEST_FAILURE, 1);
        ExecutionResult secondFailure = result(ResultStatus.TEST_FAILURE, 1);
        ExecutionResult thirdFailure = result(ResultStatus.TEST_FAILURE, 1);

        LogicalExecution logical = RetrySemantics.aggregate(List.of(
                new ExecutionAttempt(ID, 0, RetryCause.INITIAL, firstFailure),
                new ExecutionAttempt(ID, 1, RetryCause.MAVEN_RERUN, secondFailure),
                new ExecutionAttempt(ID, 2, RetryCause.MAVEN_RERUN, thirdFailure)));

        assertEquals(LogicalStatus.FAILED, logical.status());
        assertEquals(firstFailure, logical.canonicalResult());
    }

    @Test
    void doesNotTreatInfrastructureFailureAsMavenRerunCandidate() {
        ExecutionResult infrastructureFailure = result(ResultStatus.INFRASTRUCTURE_FAILURE, 2);
        ExecutionResult pass = result(ResultStatus.PASSED, 1);

        assertThrows(IllegalArgumentException.class, () -> RetrySemantics.aggregate(List.of(
                new ExecutionAttempt(ID, 0, RetryCause.INITIAL, infrastructureFailure),
                new ExecutionAttempt(ID, 1, RetryCause.MAVEN_RERUN, pass))));
    }

    @Test
    void stopsLogicalSequenceAtFirstPassingRerun() {
        assertThrows(IllegalArgumentException.class, () -> RetrySemantics.aggregate(List.of(
                new ExecutionAttempt(ID, 0, RetryCause.INITIAL, result(ResultStatus.TEST_FAILURE, 1)),
                new ExecutionAttempt(ID, 1, RetryCause.MAVEN_RERUN, result(ResultStatus.PASSED, 1)),
                new ExecutionAttempt(ID, 2, RetryCause.MAVEN_RERUN, result(ResultStatus.TEST_FAILURE, 1)))));
    }

    private ExecutionResult result(ResultStatus status, int infrastructureAttempt) {
        Instant now = Instant.parse("2026-08-30T00:00:00Z");
        return new ExecutionResult(ID, "logical test", status, Duration.ofMillis(10),
                new WorkerId("worker-1"), infrastructureAttempt, now, now.plusMillis(10),
                status == ResultStatus.PASSED ? null : "boom",
                status == ResultStatus.TEST_FAILURE ? "AssertionError" : "InfrastructureFailure");
    }
}
