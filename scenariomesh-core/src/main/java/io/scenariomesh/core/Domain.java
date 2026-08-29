package io.scenariomesh.core;

import java.io.Serializable;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class Domain {
    private Domain() {}

    public record ScenarioId(String value) implements Serializable {
        public ScenarioId {
            Objects.requireNonNull(value);
        }
    }

    public record WorkerId(String value) implements Serializable {
        public WorkerId {
            Objects.requireNonNull(value);
        }
    }

    public record RunId(String value) implements Serializable {
        public RunId {
            Objects.requireNonNull(value);
        }

        public static RunId create() {
            return new RunId(UUID.randomUUID().toString());
        }
    }

    public enum WorkerStatus {
        STARTING, READY, BUSY, IDLE, DRAINING, STOPPING, STOPPED, UNHEALTHY, DEAD
    }

    /**
     * Terminal task outcomes. SKIPPED is deliberately distinct from PASSED: it is
     * Maven-build-neutral but must never be reported as a successfully executed test.
     */
    public enum ResultStatus {
        PASSED,
        SKIPPED,
        TEST_FAILURE,
        INFRASTRUCTURE_FAILURE,
        WORKER_FAILURE,
        DISCOVERY_FAILURE,
        CONFIGURATION_FAILURE;

        public boolean buildSuccessful() {
            return this == PASSED || this == SKIPPED;
        }
    }

    public record ScenarioTask(
            ScenarioId id,
            String displayName,
            String adapterId,
            String framework,
            URI source,
            Integer line,
            String selector,
            Set<String> tags,
            Map<String, String> metadata) implements Serializable {
        public ScenarioTask {
            Objects.requireNonNull(id);
            Objects.requireNonNull(displayName);
            Objects.requireNonNull(adapterId);
            Objects.requireNonNull(framework);
            Objects.requireNonNull(selector);
            tags = Set.copyOf(tags == null ? Set.of() : tags);
            metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        }
    }

    public record ExecutionResult(
            ScenarioId scenarioId,
            String displayName,
            ResultStatus status,
            Duration duration,
            WorkerId workerId,
            int attempt,
            Instant startedAt,
            Instant finishedAt,
            String failureMessage,
            String failureType) implements Serializable {
        public ExecutionResult {
            Objects.requireNonNull(scenarioId);
            Objects.requireNonNull(displayName);
            Objects.requireNonNull(status);
            Objects.requireNonNull(duration);
            Objects.requireNonNull(workerId);
            Objects.requireNonNull(startedAt);
            Objects.requireNonNull(finishedAt);
        }

        public boolean passed() {
            return status == ResultStatus.PASSED;
        }

        public boolean skipped() {
            return status == ResultStatus.SKIPPED;
        }

        public boolean buildSuccessful() {
            return status.buildSuccessful();
        }
    }
}
