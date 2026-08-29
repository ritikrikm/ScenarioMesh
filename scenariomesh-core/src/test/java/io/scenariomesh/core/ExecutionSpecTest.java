package io.scenariomesh.core;

import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionSpecTest {
    @Test
    void adaptsFineGrainedScenarioWithoutChangingIdentityOrSelector() {
        ScenarioTask task = task(Map.of(TaskMetadata.REQUIRED_ENGINE_ID, "cucumber"));

        ExecutionSpec spec = ExecutionSpec.fromScenarioTask(task);

        assertEquals("task-1", spec.executionId());
        assertEquals(ExecutionSpec.ExecutionKind.TEST_CASE, spec.kind());
        assertEquals(ExecutionSpec.SemanticOwner.SCENARIOMESH, spec.owner());
        assertEquals("adapter", spec.backendId());
        assertEquals("feature:12", spec.selector());
        assertEquals(Set.of("cucumber"), spec.requirements().engineIds());
    }

    @Test
    void adaptsRuntimeMaterializerAsFrameworkOwnedContainer() {
        ExecutionSpec spec = ExecutionSpec.fromScenarioTask(task(Map.of(
                TaskMetadata.RUNTIME_MATERIALIZER, "true",
                TaskMetadata.PARENT_MATERIALIZER_ID, "parent")));

        assertEquals(ExecutionSpec.ExecutionKind.FRAMEWORK_CONTAINER, spec.kind());
        assertEquals(ExecutionSpec.SemanticOwner.FRAMEWORK, spec.owner());
        assertEquals("parent", spec.parentExecutionId());
    }

    @Test
    void rejectsInvalidResourceAndRetryPolicies() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionSpec.Requirements(
                Set.of(), Set.of(), Set.of(), 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionSpec.Policy(
                java.time.Duration.ZERO, java.time.Duration.ofSeconds(1),
                ExecutionSpec.RetryClass.NEVER, 1));
    }

    private ScenarioTask task(Map<String, String> metadata) {
        return new ScenarioTask(new ScenarioId("task-1"), "test", "adapter", "framework",
                null, 12, "feature:12", Set.of("smoke"), metadata);
    }
}
