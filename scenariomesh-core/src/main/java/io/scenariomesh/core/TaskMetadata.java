package io.scenariomesh.core;

/**
 * Framework-neutral ScenarioTask metadata keys shared across adapters, scheduling and coordination.
 *
 * <p>Keep this class technology-neutral. Framework/build-tool integrations may translate their
 * native concepts into these canonical keys, but core consumers must not depend on Maven, JUnit,
 * Cucumber, TestNG or transport-specific types.</p>
 */
public final class TaskMetadata {
    public static final String EXECUTION_SCOPE_ID = "executionScopeId";
    public static final String EXECUTION_SCOPE_SELECTOR = "executionScopeSelector";
    public static final String EXECUTION_SCOPE_KIND = "executionScopeKind";
    public static final String ESTIMATED_DURATION_MILLIS = "estimatedDurationMillis";
    public static final String REQUIRED_ENGINE_ID = "requiredEngineId";
    public static final String RUNTIME_MATERIALIZER = "runtimeMaterializer";
    public static final String PARENT_MATERIALIZER_ID = "parentMaterializerId";
    public static final String PARENT_MATERIALIZER_SELECTOR = "parentMaterializerSelector";

    private TaskMetadata() {}
}
