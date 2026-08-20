package io.scenariomesh.adapter.junitplatform;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import io.scenariomesh.core.ScenarioIds;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId;

public final class JUnitPlatformAdapter implements ScenarioAdapter {
    public static final String ID = "junit-platform";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String framework() {
        return "junit-platform";
    }

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
        if (context.testRoots().isEmpty()) {
            return List.of();
        }
        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClasspathRoots(new HashSet<>(context.testRoots())))
                .filters(EngineFilter.excludeEngines("junit-vintage"));
        if (!context.discoverySelection().includeClassNameRegexes().isEmpty()) {
            builder.filters(ClassNameFilter.includeClassNamePatterns(
                    context.discoverySelection().includeClassNameRegexes().toArray(String[]::new)));
        }
        if (!context.discoverySelection().excludeClassNameRegexes().isEmpty()) {
            builder.filters(ClassNameFilter.excludeClassNamePatterns(
                    context.discoverySelection().excludeClassNameRegexes().toArray(String[]::new)));
        }

        Launcher launcher = LauncherFactory.create();
        TestPlan plan = launcher.discover(builder.build());
        List<ScenarioTask> tasks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TestIdentifier root : plan.getRoots()) {
            for (TestIdentifier identifier : plan.getDescendants(root)) {
                if (!identifier.isTest() || !plan.getChildren(identifier).isEmpty()) {
                    continue;
                }
                String uniqueId = identifier.getUniqueId();
                if (!seen.add(uniqueId)) {
                    continue;
                }
                Set<String> tags = new HashSet<>();
                for (TestTag tag : identifier.getTags()) {
                    tags.add(tag.getName());
                }
                String framework = uniqueId.contains("[engine:cucumber]")
                        ? "cucumber-junit-platform"
                        : "junit5";
                tasks.add(new ScenarioTask(
                        ScenarioIds.from(ID, uniqueId),
                        identifier.getDisplayName(),
                        ID,
                        framework,
                        null,
                        null,
                        uniqueId,
                        tags,
                        Map.of("uniqueId", uniqueId)));
            }
        }
        return List.copyOf(tasks);
    }

    @Override
    public ExecutionResult execute(ScenarioTask task, ExecutionContext context) {
        Instant started = Instant.now();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectUniqueId(task.selector()))
                .filters(EngineFilter.excludeEngines("junit-vintage"))
                .build();
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        Instant finished = Instant.now();
        return classify(task, context, started, finished, listener.getSummary());
    }

    private ExecutionResult classify(
            ScenarioTask task,
            ExecutionContext context,
            Instant started,
            Instant finished,
            TestExecutionSummary summary) {
        Duration duration = Duration.between(started, finished);
        long found = summary.getTestsFoundCount();
        long startedCount = summary.getTestsStartedCount();
        long succeeded = summary.getTestsSucceededCount();
        long failed = summary.getTestsFailedCount();
        long skipped = summary.getTestsSkippedCount();
        long aborted = summary.getTestsAbortedCount();

        if (found == 0) {
            return infrastructureFailure(task, context, started, finished,
                    "JUnit Platform did not execute the selected test: " + task.selector(),
                    "SelectionFailure");
        }

        // ScenarioMesh dispatches one discovered leaf task at a time. If a unique-id
        // selection expands to several terminal tests, we cannot safely attribute one
        // aggregate result back to this ScenarioTask.
        if (found != 1) {
            return infrastructureFailure(task, context, started, finished,
                    "JUnit Platform selection for " + task.selector() + " resolved to " + found
                            + " tests; ScenarioMesh requires exactly one terminal test per task",
                    "SelectionMultiplicityFailure");
        }

        if (failed > 0) {
            Throwable failure = summary.getFailures().isEmpty()
                    ? null
                    : summary.getFailures().get(0).getException();
            return new ExecutionResult(
                    task.id(),
                    task.displayName(),
                    ResultStatus.TEST_FAILURE,
                    duration,
                    context.workerId(),
                    context.attempt(),
                    started,
                    finished,
                    failure == null ? "Test failed" : safeMessage(failure),
                    failure == null ? null : failure.getClass().getName());
        }

        if (succeeded == 1 && startedCount == 1 && skipped == 0 && aborted == 0) {
            return new ExecutionResult(
                    task.id(), task.displayName(), ResultStatus.PASSED, duration,
                    context.workerId(), context.attempt(), started, finished, null, null);
        }

        if (succeeded == 0 && failed == 0 && (skipped == 1 || aborted == 1)) {
            String reason = aborted == 1
                    ? "JUnit Platform aborted the selected test"
                    : "JUnit Platform skipped the selected test";
            return new ExecutionResult(
                    task.id(), task.displayName(), ResultStatus.SKIPPED, duration,
                    context.workerId(), context.attempt(), started, finished,
                    reason, aborted == 1 ? "JUnitAborted" : "JUnitSkipped");
        }

        return infrastructureFailure(
                task,
                context,
                started,
                finished,
                "JUnit Platform produced an ambiguous terminal summary for " + task.selector()
                        + " (found=" + found
                        + ", started=" + startedCount
                        + ", succeeded=" + succeeded
                        + ", failed=" + failed
                        + ", skipped=" + skipped
                        + ", aborted=" + aborted + ")",
                "ExecutionSummaryFailure");
    }

    private ExecutionResult infrastructureFailure(
            ScenarioTask task,
            ExecutionContext context,
            Instant started,
            Instant finished,
            String message,
            String type) {
        return new ExecutionResult(
                task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                Duration.between(started, finished), context.workerId(), context.attempt(),
                started, finished, message, type);
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getName() : message;
    }
}
