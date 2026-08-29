package io.scenariomesh.core;

import io.scenariomesh.core.Domain.ScenarioTask;

import java.io.Serializable;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Transport-neutral description of work that ScenarioMesh may schedule. */
public record ExecutionSpec(
        String executionId,
        String parentExecutionId,
        ExecutionKind kind,
        SemanticOwner owner,
        String displayName,
        String backendId,
        String selector,
        Requirements requirements,
        Policy policy,
        Map<String, String> attributes) implements Serializable {

    public ExecutionSpec {
        executionId = required(executionId, "executionId");
        parentExecutionId = optional(parentExecutionId);
        kind = Objects.requireNonNull(kind, "kind");
        owner = Objects.requireNonNull(owner, "owner");
        displayName = required(displayName, "displayName");
        backendId = required(backendId, "backendId");
        selector = required(selector, "selector");
        requirements = requirements == null ? Requirements.none() : requirements;
        policy = policy == null ? Policy.defaults() : policy;
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        if (attributes.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null)) {
            throw new IllegalArgumentException("attributes require non-blank keys and non-null values");
        }
    }

    /** Lossless compatibility adapter for the existing v8/v9 fine-grained task model. */
    public static ExecutionSpec fromScenarioTask(ScenarioTask task) {
        Objects.requireNonNull(task, "task");
        String requiredEngine = task.metadata().get(TaskMetadata.REQUIRED_ENGINE_ID);
        return new ExecutionSpec(
                task.id().value(),
                optional(task.metadata().get(TaskMetadata.PARENT_MATERIALIZER_ID)),
                runtimeMaterializer(task) ? ExecutionKind.FRAMEWORK_CONTAINER : ExecutionKind.TEST_CASE,
                runtimeMaterializer(task) ? SemanticOwner.FRAMEWORK : SemanticOwner.SCENARIOMESH,
                task.displayName(),
                task.adapterId(),
                task.selector(),
                new Requirements(Set.of(task.adapterId()),
                        requiredEngine == null || requiredEngine.isBlank() ? Set.of() : Set.of(requiredEngine),
                        Set.of(), 1, 0L, 1),
                Policy.defaults(),
                task.metadata());
    }

    private static boolean runtimeMaterializer(ScenarioTask task) {
        return Boolean.parseBoolean(task.metadata().getOrDefault(TaskMetadata.RUNTIME_MATERIALIZER, "false"));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public enum ExecutionKind {
        TEST_CASE,
        FRAMEWORK_CONTAINER,
        MAVEN_PLUGIN_EXECUTION
    }

    public enum SemanticOwner {
        SCENARIOMESH,
        FRAMEWORK,
        BUILD_TOOL
    }

    public record Requirements(
            Set<String> adapterIds,
            Set<String> engineIds,
            Set<String> labels,
            int cpuUnits,
            long memoryBytes,
            int internalParallelism) implements Serializable {

        public Requirements {
            adapterIds = clean(adapterIds, "adapterIds");
            engineIds = clean(engineIds, "engineIds");
            labels = clean(labels, "labels");
            if (cpuUnits < 1) throw new IllegalArgumentException("cpuUnits must be positive");
            if (memoryBytes < 0L) throw new IllegalArgumentException("memoryBytes must not be negative");
            if (internalParallelism < 1) {
                throw new IllegalArgumentException("internalParallelism must be positive");
            }
        }

        public static Requirements none() {
            return new Requirements(Set.of(), Set.of(), Set.of(), 1, 0L, 1);
        }

        private static Set<String> clean(Set<String> values, String name) {
            Set<String> copy = Set.copyOf(values == null ? Set.of() : values);
            if (copy.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException(name + " must not contain blank values");
            }
            return copy;
        }
    }

    public record Policy(
            Duration timeout,
            Duration cancellationGrace,
            RetryClass retryClass,
            int maxAttempts) implements Serializable {

        public Policy {
            timeout = positive(timeout, "timeout");
            cancellationGrace = positive(cancellationGrace, "cancellationGrace");
            retryClass = Objects.requireNonNull(retryClass, "retryClass");
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        }

        public static Policy defaults() {
            return new Policy(Duration.ofMinutes(30), Duration.ofSeconds(10),
                    RetryClass.INFRASTRUCTURE_ONLY, 1);
        }

        private static Duration positive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }

    public enum RetryClass {
        NEVER,
        INFRASTRUCTURE_ONLY,
        OWNER_MANAGED
    }
}
