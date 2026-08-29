package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.WorkerId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfrastructureRetryPolicyTest {
    @Test
    void retriesEveryLeafInLifecycleScopedUnitAfterWorkerLoss() {
        List<ExecutionResult> failures = List.of(
                result("one", ResultStatus.WORKER_FAILURE),
                result("two", ResultStatus.WORKER_FAILURE));

        assertTrue(InfrastructureRetryPolicy.retryable(2, failures));
    }

    @Test
    void rejectsPartialOrMixedTerminalResults() {
        assertFalse(InfrastructureRetryPolicy.retryable(2,
                List.of(result("one", ResultStatus.WORKER_FAILURE))));
        assertFalse(InfrastructureRetryPolicy.retryable(2, List.of(
                result("one", ResultStatus.WORKER_FAILURE),
                result("two", ResultStatus.PASSED))));
    }

    private ExecutionResult result(String id, ResultStatus status) {
        Instant now = Instant.now();
        return new ExecutionResult(new ScenarioId(id), id, status, Duration.ZERO,
                new WorkerId("worker-1"), 1, now, now, null, null);
    }
}
