package io.scenariomesh.core;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Maven-compatible logical retry model.
 *
 * <p>Infrastructure attempts remain owned by {@link ExecutionResult#attempt()}.
 * Maven reruns are a separate logical dimension so lease recovery can never consume
 * a configured test rerun or accidentally change flaky-test accounting.</p>
 */
public final class RetrySemantics {
    private RetrySemantics() {}

    public enum RetryCause {
        INITIAL,
        MAVEN_RERUN
    }

    public enum LogicalStatus {
        PASSED,
        SKIPPED,
        FLAKY,
        FAILED
    }

    public record ExecutionAttempt(
            ScenarioId logicalTask,
            int rerunIndex,
            RetryCause cause,
            ExecutionResult result) implements Serializable {
        public ExecutionAttempt {
            Objects.requireNonNull(logicalTask);
            Objects.requireNonNull(cause);
            Objects.requireNonNull(result);
            if (rerunIndex < 0) throw new IllegalArgumentException("rerunIndex must be >= 0");
            if (!logicalTask.equals(result.scenarioId())) {
                throw new IllegalArgumentException("attempt result must belong to logical task " + logicalTask.value());
            }
            if (rerunIndex == 0 && cause != RetryCause.INITIAL) {
                throw new IllegalArgumentException("rerunIndex 0 must use INITIAL cause");
            }
            if (rerunIndex > 0 && cause != RetryCause.MAVEN_RERUN) {
                throw new IllegalArgumentException("rerunIndex > 0 must use MAVEN_RERUN cause");
            }
        }
    }

    public record LogicalExecution(
            ScenarioId logicalTask,
            List<ExecutionAttempt> attempts,
            LogicalStatus status,
            ExecutionResult canonicalResult) implements Serializable {
        public LogicalExecution {
            Objects.requireNonNull(logicalTask);
            attempts = List.copyOf(Objects.requireNonNull(attempts));
            Objects.requireNonNull(status);
            Objects.requireNonNull(canonicalResult);
            if (attempts.isEmpty()) throw new IllegalArgumentException("logical execution requires at least one attempt");
        }

        public boolean flaky() {
            return status == LogicalStatus.FLAKY;
        }
    }

    /**
     * Aggregates an initial test result plus Maven reruns using Surefire's observable rules:
     * a later pass is flaky; if every run fails, the first failure remains canonical.
     */
    public static LogicalExecution aggregate(List<ExecutionAttempt> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            throw new IllegalArgumentException("attempts must not be empty");
        }
        List<ExecutionAttempt> ordered = new ArrayList<>(attempts);
        ordered.sort(java.util.Comparator.comparingInt(ExecutionAttempt::rerunIndex));
        ScenarioId id = ordered.get(0).logicalTask();
        for (int index = 0; index < ordered.size(); index++) {
            ExecutionAttempt attempt = ordered.get(index);
            if (!id.equals(attempt.logicalTask())) throw new IllegalArgumentException("all attempts must share one logical task");
            if (attempt.rerunIndex() != index) throw new IllegalArgumentException("rerun indexes must be contiguous from 0");
        }

        ExecutionResult first = ordered.get(0).result();
        if (first.status() == ResultStatus.PASSED) {
            requireSingleAttempt(ordered, "a passed initial execution must not be rerun");
            return new LogicalExecution(id, ordered, LogicalStatus.PASSED, first);
        }
        if (first.status() == ResultStatus.SKIPPED) {
            requireSingleAttempt(ordered, "a skipped initial execution must not be rerun");
            return new LogicalExecution(id, ordered, LogicalStatus.SKIPPED, first);
        }
        if (first.status() != ResultStatus.TEST_FAILURE) {
            requireSingleAttempt(ordered, "infrastructure/configuration outcomes are not Maven-rerun eligible");
            return new LogicalExecution(id, ordered, LogicalStatus.FAILED, first);
        }

        for (int index = 1; index < ordered.size(); index++) {
            ExecutionResult result = ordered.get(index).result();
            if (result.status() == ResultStatus.PASSED) {
                if (index != ordered.size() - 1) {
                    throw new IllegalArgumentException("Maven reruns must stop after the first passing rerun");
                }
                return new LogicalExecution(id, ordered, LogicalStatus.FLAKY, result);
            }
            if (result.status() != ResultStatus.TEST_FAILURE) {
                throw new IllegalArgumentException("Maven reruns may only contain test failures before a pass");
            }
        }
        return new LogicalExecution(id, ordered, LogicalStatus.FAILED, first);
    }

    private static void requireSingleAttempt(List<ExecutionAttempt> attempts, String message) {
        if (attempts.size() != 1) throw new IllegalArgumentException(message);
    }
}
