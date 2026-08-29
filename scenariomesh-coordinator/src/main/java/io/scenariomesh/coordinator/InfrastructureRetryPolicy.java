package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;

import java.util.List;

final class InfrastructureRetryPolicy {
    private InfrastructureRetryPolicy() {
    }

    static boolean retryable(int expectedResults, List<ExecutionResult> results) {
        return expectedResults > 0
                && results.size() == expectedResults
                && results.stream().allMatch(InfrastructureRetryPolicy::retryable);
    }

    private static boolean retryable(ExecutionResult result) {
        return result.status() == ResultStatus.WORKER_FAILURE
                || result.status() == ResultStatus.INFRASTRUCTURE_FAILURE;
    }
}
