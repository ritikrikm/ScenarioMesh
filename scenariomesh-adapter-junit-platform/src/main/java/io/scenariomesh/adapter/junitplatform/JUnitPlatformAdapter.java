package io.scenariomesh.adapter.junitplatform;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import io.scenariomesh.core.Ports.WorkUnitExecution;
import io.scenariomesh.core.ScenarioIds;
import io.scenariomesh.core.TaskMetadata;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId;

public final class JUnitPlatformAdapter implements ScenarioAdapter {
    public static final String ID = "junit-platform";
    public static final String META_SCOPE_ID = TaskMetadata.EXECUTION_SCOPE_ID;
    public static final String META_SCOPE_SELECTOR = TaskMetadata.EXECUTION_SCOPE_SELECTOR;
    public static final String META_SCOPE_KIND = TaskMetadata.EXECUTION_SCOPE_KIND;
    public static final String META_RUNTIME_MATERIALIZER = TaskMetadata.RUNTIME_MATERIALIZER;
    public static final String META_PARENT_MATERIALIZER_ID = TaskMetadata.PARENT_MATERIALIZER_ID;
    public static final String META_PARENT_MATERIALIZER_SELECTOR = TaskMetadata.PARENT_MATERIALIZER_SELECTOR;
    /** Generic scheduler requirement published by the adapter that owns JUnit UniqueId semantics. */
    public static final String META_REQUIRED_ENGINE_ID = TaskMetadata.REQUIRED_ENGINE_ID;

    @Override public String id() { return ID; }
    @Override public String framework() { return "junit-platform"; }

    @Override
    public boolean isAvailable(ClassLoader classLoader) {
        try {
            Class.forName("org.junit.platform.launcher.Launcher", false, classLoader);
            return ServiceLoader.load(TestEngine.class, classLoader).iterator().hasNext();
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    @Override
    public List<ScenarioTask> discover(AdapterContext context) {
        if (context.testRoots().isEmpty()) return List.of();
        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClasspathRoots(new HashSet<>(context.testRoots())))
                .filters(EngineFilter.excludeEngines("junit-vintage"));
        if (!context.discoverySelection().includeClassNameRegexes().isEmpty()
                || !context.discoverySelection().excludeClassNameRegexes().isEmpty()) {
            builder.filters(new MavenClassSelectionPostFilter(context.discoverySelection()));
        }

        TestPlan plan = LauncherFactory.create().discover(builder.build());
        List<TestIdentifier> candidates = new ArrayList<>();
        for (TestIdentifier root : plan.getRoots()) {
            for (TestIdentifier identifier : plan.getDescendants(root)) {
                if ((identifier.isTest() && plan.getChildren(identifier).isEmpty()) || isRuntimeMaterializer(identifier)) {
                    candidates.add(identifier);
                }
            }
        }

        List<TestIdentifier> mergedCandidates = new DiscoveredExecutionMerger().merge(candidates);
        List<ScenarioTask> tasks = new ArrayList<>();
        for (TestIdentifier identifier : mergedCandidates) {
            ExecutionScope scope = executionScope(plan, identifier);
            tasks.add(taskFor(identifier, scope, null));
        }
        return List.copyOf(tasks);
    }

    @Override
    public ExecutionResult execute(ScenarioTask task, ExecutionContext context) {
        WorkUnitExecution execution = executeWorkUnit(List.of(task), context);
        return execution.results().get(0);
    }

    @Override
    public List<ExecutionResult> executeBatch(List<ScenarioTask> tasks, ExecutionContext context) {
        return executeWorkUnit(tasks, context).results();
    }

    @Override
    public WorkUnitExecution executeWorkUnit(List<ScenarioTask> tasks, ExecutionContext context) {
        validateScope(tasks);
        ScopeExecution execution = executeScope(tasks);
        List<ScenarioTask> concreteTasks = new ArrayList<>();
        List<ExecutionResult> concreteResults = new ArrayList<>();

        for (ScenarioTask task : tasks) {
            if (!isMaterializer(task)) {
                concreteTasks.add(task);
                concreteResults.add(resultFor(task, execution.outcomes().get(task.selector()), context));
                continue;
            }

            List<TestIdentifier> children = execution.identifiers().values().stream()
                    .filter(TestIdentifier::isTest)
                    .filter(identifier -> identifier.getUniqueId().startsWith(task.selector() + "/"))
                    .filter(identifier -> execution.outcomes().containsKey(identifier.getUniqueId()))
                    .sorted(Comparator.comparing(TestIdentifier::getUniqueId))
                    .toList();
            if (children.isEmpty()) {
                concreteTasks.add(task);
                concreteResults.add(resultFor(task, null, context));
                continue;
            }
            for (TestIdentifier child : children) {
                ScenarioTask materialized = materializedTask(task, child);
                concreteTasks.add(materialized);
                concreteResults.add(resultFor(materialized, execution.outcomes().get(child.getUniqueId()), context));
            }
        }
        return new WorkUnitExecution(concreteTasks, concreteResults);
    }

    private void validateScope(List<ScenarioTask> tasks) {
        if (tasks == null || tasks.isEmpty()) throw new IllegalArgumentException("JUnitPlatformAdapter requires at least one task");
        String scopeId = scopeId(tasks.get(0));
        String scopeSelector = scopeSelector(tasks.get(0));
        for (ScenarioTask task : tasks) {
            if (!scopeId.equals(scopeId(task)) || !scopeSelector.equals(scopeSelector(task))) {
                throw new IllegalArgumentException("JUnit Platform batch mixes lifecycle scopes; expected scope=" + scopeId
                        + " but received task " + task.id().value() + " scope=" + scopeId(task));
            }
        }
    }

    private ScopeExecution executeScope(List<ScenarioTask> tasks) {
        ScopedResultListener listener = new ScopedResultListener();
        List<DiscoverySelector> selectors = tasks.stream()
                .map(task -> (DiscoverySelector) selectUniqueId(task.selector())).toList();
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors).filters(EngineFilter.excludeEngines("junit-vintage")).build();
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        return new ScopeExecution(listener.identifiers(), listener.outcomes());
    }

    private ScenarioTask taskFor(TestIdentifier identifier, ExecutionScope scope, ScenarioTask parentMaterializer) {
        String uniqueId = identifier.getUniqueId();
        Set<String> tags = new HashSet<>();
        for (TestTag tag : identifier.getTags()) tags.add(tag.getName());
        String engineId = requiredEngineId(uniqueId);
        String framework = "cucumber".equals(engineId) ? "cucumber-junit-platform" : "junit5";
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("uniqueId", uniqueId);
        metadata.put(META_REQUIRED_ENGINE_ID, engineId);
        metadata.put(META_SCOPE_ID, scope.id());
        metadata.put(META_SCOPE_SELECTOR, scope.selector());
        metadata.put(META_SCOPE_KIND, scope.kind());
        if (isRuntimeMaterializer(identifier)) metadata.put(META_RUNTIME_MATERIALIZER, "true");
        if (parentMaterializer != null) {
            metadata.put(META_PARENT_MATERIALIZER_ID, parentMaterializer.id().value());
            metadata.put(META_PARENT_MATERIALIZER_SELECTOR, parentMaterializer.selector());
        }
        return new ScenarioTask(ScenarioIds.from(ID, uniqueId), identifier.getDisplayName(), ID, framework,
                null, null, uniqueId, tags, metadata);
    }

    private String requiredEngineId(String uniqueId) {
        return UniqueId.parse(uniqueId).getEngineId()
                .orElseThrow(() -> new IllegalStateException("JUnit Platform UniqueId has no engine id: " + uniqueId));
    }

    private ScenarioTask materializedTask(ScenarioTask parent, TestIdentifier child) {
        ExecutionScope scope = new ExecutionScope(scopeId(parent), scopeSelector(parent),
                parent.metadata().getOrDefault(META_SCOPE_KIND, "class-or-suite"));
        return taskFor(child, scope, parent);
    }

    private ExecutionResult resultFor(ScenarioTask task, CachedOutcome outcome, ExecutionContext context) {
        if (outcome == null) {
            Instant now = Instant.now();
            return new ExecutionResult(task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                    Duration.ZERO, context.workerId(), context.attempt(), now, now,
                    "JUnit Platform lifecycle scope did not produce the selected/materialized test " + task.selector(),
                    "ScopedSelectionFailure");
        }
        return outcome.toResult(task, context);
    }

    /**
     * Runtime materialization is currently a proven Jupiter capability only. Do not infer dynamic
     * execution semantics from another engine merely because its UniqueId happens to reuse a
     * segment name such as test-template/test-factory.
     */
    private static boolean isRuntimeMaterializer(TestIdentifier identifier) {
        String id = identifier.getUniqueId();
        String engineId;
        try {
            engineId = UniqueId.parse(id).getEngineId().orElse("");
        } catch (RuntimeException invalid) {
            return false;
        }
        return "junit-jupiter".equals(engineId)
                && (id.contains("[test-template:") || id.contains("[test-factory:"));
    }

    private static boolean isMaterializer(ScenarioTask task) {
        return Boolean.parseBoolean(task.metadata().getOrDefault(META_RUNTIME_MATERIALIZER, "false"));
    }

    private String scopeId(ScenarioTask task) { return task.metadata().getOrDefault(META_SCOPE_ID, task.selector()); }
    private String scopeSelector(ScenarioTask task) { return task.metadata().getOrDefault(META_SCOPE_SELECTOR, task.selector()); }

    private ExecutionScope executionScope(TestPlan plan, TestIdentifier leaf) {
        TestIdentifier current = leaf;
        while (true) {
            Optional<TestIdentifier> parent = plan.getParent(current);
            if (parent.isEmpty()) break;
            current = parent.get();
            if (hasClassSource(current)) return new ExecutionScope(current.getUniqueId(), current.getUniqueId(), "class-or-suite");
        }
        TestIdentifier root = rootOf(plan, leaf);
        return new ExecutionScope(root.getUniqueId(), root.getUniqueId(), "engine-run");
    }

    private boolean hasClassSource(TestIdentifier identifier) {
        Optional<TestSource> source = identifier.getSource();
        return source.isPresent() && source.get() instanceof ClassSource;
    }

    private TestIdentifier rootOf(TestPlan plan, TestIdentifier identifier) {
        TestIdentifier current = identifier;
        for (;;) {
            Optional<TestIdentifier> parent = plan.getParent(current);
            if (parent.isEmpty()) return current;
            current = parent.get();
        }
    }

    private static final class ScopedResultListener implements TestExecutionListener {
        private final Map<String, Instant> starts = new HashMap<>();
        private final Map<String, TestIdentifier> identifiers = new LinkedHashMap<>();
        private final Map<String, CachedOutcome> outcomes = new LinkedHashMap<>();

        @Override public void dynamicTestRegistered(TestIdentifier identifier) { identifiers.put(identifier.getUniqueId(), identifier); }
        @Override public void executionStarted(TestIdentifier identifier) {
            identifiers.put(identifier.getUniqueId(), identifier);
            if (identifier.isTest()) starts.put(identifier.getUniqueId(), Instant.now());
        }
        @Override public void executionSkipped(TestIdentifier identifier, String reason) {
            identifiers.put(identifier.getUniqueId(), identifier);
            if (!identifier.isTest()) return;
            Instant now = Instant.now();
            outcomes.put(identifier.getUniqueId(), new CachedOutcome(ResultStatus.SKIPPED, now, now,
                    reason == null || reason.isBlank() ? "JUnit Platform skipped the selected test" : reason, "JUnitSkipped"));
        }
        @Override public void executionFinished(TestIdentifier identifier, TestExecutionResult executionResult) {
            identifiers.put(identifier.getUniqueId(), identifier);
            if (!identifier.isTest()) return;
            Instant finished = Instant.now();
            Instant started = starts.remove(identifier.getUniqueId());
            if (started == null) started = finished;
            ResultStatus status;
            String message = null, type = null;
            switch (executionResult.getStatus()) {
                case SUCCESSFUL -> status = ResultStatus.PASSED;
                case ABORTED -> { status = ResultStatus.SKIPPED; message = executionResult.getThrowable()
                        .map(JUnitPlatformAdapter::safeThrowableMessage).orElse("JUnit Platform aborted the selected test"); type = "JUnitAborted"; }
                case FAILED -> { status = ResultStatus.TEST_FAILURE; Throwable failure = executionResult.getThrowable().orElse(null);
                    message = failure == null ? "Test failed" : safeThrowableMessage(failure); type = failure == null ? null : failure.getClass().getName(); }
                default -> throw new IllegalStateException("Unknown JUnit Platform execution status: " + executionResult.getStatus());
            }
            outcomes.put(identifier.getUniqueId(), new CachedOutcome(status, started, finished, message, type));
        }
        Map<String, TestIdentifier> identifiers() { return Map.copyOf(identifiers); }
        Map<String, CachedOutcome> outcomes() { return Map.copyOf(outcomes); }
    }

    private record ScopeExecution(Map<String, TestIdentifier> identifiers, Map<String, CachedOutcome> outcomes) {}
    private record ExecutionScope(String id, String selector, String kind) {}
    private record CachedOutcome(ResultStatus status, Instant started, Instant finished, String failureMessage, String failureType) {
        ExecutionResult toResult(ScenarioTask task, ExecutionContext context) {
            return new ExecutionResult(task.id(), task.displayName(), status, Duration.between(started, finished),
                    context.workerId(), context.attempt(), started, finished, failureMessage, failureType);
        }
    }
    private static String safeThrowableMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getName() : message;
    }
}
