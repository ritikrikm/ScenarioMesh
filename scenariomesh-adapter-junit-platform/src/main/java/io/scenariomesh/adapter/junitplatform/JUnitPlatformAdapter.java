package io.scenariomesh.adapter.junitplatform;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import io.scenariomesh.core.ScenarioIds;
import io.scenariomesh.core.SelectedTestClasses;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId;

public final class JUnitPlatformAdapter implements ScenarioAdapter {
    public static final String ID = "junit-platform";
    public static final String META_SCOPE_ID = "executionScopeId";
    public static final String META_SCOPE_SELECTOR = "executionScopeSelector";
    public static final String META_SCOPE_KIND = "executionScopeKind";

    private final Map<String, Map<String, CachedOutcome>> scopedResults = new HashMap<>();

    @Override
    public String id() { return ID; }

    @Override
    public String framework() { return "junit-platform"; }

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
                .filters(EngineFilter.excludeEngines("junit-vintage"));
        if (context.discoverySelection().includeClassNameRegexes().isEmpty()
                && context.discoverySelection().excludeClassNameRegexes().isEmpty()) {
            builder.selectors(selectClasspathRoots(new HashSet<>(context.testRoots())));
        } else {
            List<String> selectedClasses = SelectedTestClasses.scan(context.testRoots(), context.discoverySelection());
            if (selectedClasses.isEmpty()) {
                builder.selectors(selectClasspathRoots(new HashSet<>(context.testRoots())));
            } else {
                List<DiscoverySelector> selectors = selectedClasses.stream()
                        .map(className -> (DiscoverySelector) selectClass(className))
                        .toList();
                builder.selectors(selectors);
            }
        }

        Launcher launcher = LauncherFactory.create();
        TestPlan plan = launcher.discover(builder.build());
        List<TestIdentifier> discoveredLeaves = new ArrayList<>();
        for (TestIdentifier root : plan.getRoots()) {
            for (TestIdentifier identifier : plan.getDescendants(root)) {
                if (identifier.isTest() && plan.getChildren(identifier).isEmpty()) discoveredLeaves.add(identifier);
            }
        }

        List<ScenarioTask> tasks = new ArrayList<>();
        for (TestIdentifier identifier : discoveredLeaves) {
            String uniqueId = identifier.getUniqueId();
            Set<String> tags = new HashSet<>();
            for (TestTag tag : identifier.getTags()) tags.add(tag.getName());
            String framework = uniqueId.contains("[engine:cucumber]")
                    ? "cucumber-junit-platform"
                    : "junit5";
            ExecutionScope scope = executionScope(plan, identifier);
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("uniqueId", uniqueId);
            metadata.put(META_SCOPE_ID, scope.id());
            metadata.put(META_SCOPE_SELECTOR, scope.selector());
            metadata.put(META_SCOPE_KIND, scope.kind());
            tasks.add(new ScenarioTask(
                    ScenarioIds.from(ID, uniqueId),
                    identifier.getDisplayName(),
                    ID,
                    framework,
                    null,
                    null,
                    uniqueId,
                    tags,
                    metadata));
        }
        return List.copyOf(tasks);
    }

    @Override
    public ExecutionResult execute(ScenarioTask task, ExecutionContext context) {
        return executeBatch(List.of(task), context).get(0);
    }

    @Override
    public List<ExecutionResult> executeBatch(List<ScenarioTask> tasks, ExecutionContext context) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("JUnitPlatformAdapter requires at least one task");
        }
        String scopeId = scopeId(tasks.get(0));
        String scopeSelector = scopeSelector(tasks.get(0));
        for (ScenarioTask task : tasks) {
            if (!scopeId.equals(scopeId(task)) || !scopeSelector.equals(scopeSelector(task))) {
                throw new IllegalArgumentException(
                        "JUnit Platform batch mixes lifecycle scopes; expected scope=" + scopeId
                                + " but received task " + task.id().value() + " scope=" + scopeId(task));
            }
        }

        Map<String, CachedOutcome> outcomes = scopedResults.computeIfAbsent(
                scopeId, ignored -> executeScope(scopeSelector));
        List<ExecutionResult> results = new ArrayList<>(tasks.size());
        for (ScenarioTask task : tasks) {
            CachedOutcome outcome = outcomes.get(task.selector());
            if (outcome == null) {
                Instant now = Instant.now();
                results.add(new ExecutionResult(
                        task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                        Duration.ZERO, context.workerId(), context.attempt(), now, now,
                        "JUnit Platform lifecycle scope '" + scopeSelector
                                + "' did not produce the discovered leaf " + task.selector(),
                        "ScopedSelectionFailure"));
            } else {
                results.add(outcome.toResult(task, context));
            }
        }
        return List.copyOf(results);
    }

    private String scopeId(ScenarioTask task) {
        return task.metadata().getOrDefault(META_SCOPE_ID, task.selector());
    }

    private String scopeSelector(ScenarioTask task) {
        return task.metadata().getOrDefault(META_SCOPE_SELECTOR, task.selector());
    }

    private Map<String, CachedOutcome> executeScope(String scopeSelector) {
        ScopedResultListener listener = new ScopedResultListener();
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectUniqueId(scopeSelector))
                .filters(EngineFilter.excludeEngines("junit-vintage"))
                .build();
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        return listener.outcomes();
    }

    private ExecutionScope executionScope(TestPlan plan, TestIdentifier leaf) {
        TestIdentifier current = leaf;
        while (true) {
            Optional<TestIdentifier> parent = plan.getParent(current);
            if (parent.isEmpty()) break;
            current = parent.get();
            if (hasClassSource(current)) {
                return new ExecutionScope(current.getUniqueId(), current.getUniqueId(), "class-or-suite");
            }
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
        private final Map<String, CachedOutcome> outcomes = new LinkedHashMap<>();

        @Override
        public void executionStarted(TestIdentifier testIdentifier) {
            if (testIdentifier.isTest()) starts.put(testIdentifier.getUniqueId(), Instant.now());
        }

        @Override
        public void executionSkipped(TestIdentifier testIdentifier, String reason) {
            if (!testIdentifier.isTest()) return;
            Instant now = Instant.now();
            outcomes.put(testIdentifier.getUniqueId(), new CachedOutcome(
                    ResultStatus.SKIPPED, now, now,
                    reason == null || reason.isBlank() ? "JUnit Platform skipped the selected test" : reason,
                    "JUnitSkipped"));
        }

        @Override
        public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
            if (!testIdentifier.isTest()) return;
            Instant finished = Instant.now();
            Instant started = starts.remove(testIdentifier.getUniqueId());
            if (started == null) started = finished;
            ResultStatus status;
            String message = null;
            String type = null;
            switch (testExecutionResult.getStatus()) {
                case SUCCESSFUL -> status = ResultStatus.PASSED;
                case ABORTED -> {
                    status = ResultStatus.SKIPPED;
                    message = testExecutionResult.getThrowable()
                            .map(JUnitPlatformAdapter::safeThrowableMessage)
                            .orElse("JUnit Platform aborted the selected test");
                    type = "JUnitAborted";
                }
                case FAILED -> {
                    status = ResultStatus.TEST_FAILURE;
                    Throwable failure = testExecutionResult.getThrowable().orElse(null);
                    message = failure == null ? "Test failed" : safeThrowableMessage(failure);
                    type = failure == null ? null : failure.getClass().getName();
                }
                default -> throw new IllegalStateException("Unknown JUnit Platform execution status: " + testExecutionResult.getStatus());
            }
            outcomes.put(testIdentifier.getUniqueId(), new CachedOutcome(status, started, finished, message, type));
        }

        Map<String, CachedOutcome> outcomes() { return Map.copyOf(outcomes); }
    }

    private record ExecutionScope(String id, String selector, String kind) {}

    private record CachedOutcome(
            ResultStatus status,
            Instant started,
            Instant finished,
            String failureMessage,
            String failureType) {
        ExecutionResult toResult(ScenarioTask task, ExecutionContext context) {
            return new ExecutionResult(
                    task.id(), task.displayName(), status,
                    Duration.between(started, finished), context.workerId(), context.attempt(),
                    started, finished, failureMessage, failureType);
        }
    }

    private static String safeThrowableMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getName() : message;
    }
}
