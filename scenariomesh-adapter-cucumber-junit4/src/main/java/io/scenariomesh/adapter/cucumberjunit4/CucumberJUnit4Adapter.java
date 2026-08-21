package io.scenariomesh.adapter.cucumberjunit4;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import io.scenariomesh.core.ScenarioIds;
import junit.framework.TestCase;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class CucumberJUnit4Adapter implements ScenarioAdapter {
    public static final String ID = "cucumber-junit4";
    private static final Set<String> CUCUMBER_RUNNERS = Set.of(
            "io.cucumber.junit.Cucumber",
            "cucumber.api.junit.Cucumber");

    private final JUnit4DescriptionLeaves descriptionLeaves = new JUnit4DescriptionLeaves();
    private final CucumberJsonReportIsolation jsonReportIsolation = new CucumberJsonReportIsolation();

    @Override public String id() { return ID; }
    @Override public String framework() { return "cucumber-junit4"; }

    @Override
    public boolean isAvailable(ClassLoader classLoader) {
        return present(classLoader, "org.junit.runner.JUnitCore")
                && (present(classLoader, "io.cucumber.junit.Cucumber")
                || present(classLoader, "cucumber.api.junit.Cucumber"));
    }

    @Override
    public List<ScenarioTask> discover(AdapterContext context) throws Exception {
        List<ScenarioTask> tasks = new ArrayList<>();
        for (Class<?> runner : findCucumberRunnerClasses(context)) {
            Description root = Request.aClass(runner).getRunner().getDescription();
            for (JUnit4DescriptionLeaves.Leaf leaf : descriptionLeaves.collect(root)) {
                String selector = new Selector(runner.getName(), leaf.selectorPath(), leaf.semanticKey()).encode();
                Description description = leaf.description();
                tasks.add(new ScenarioTask(
                        ScenarioIds.from(ID, selector), description.getDisplayName(), ID, framework(),
                        null, null, selector, Set.of(),
                        Map.of("runnerClass", runner.getName(),
                                "frameworkDescription", leaf.semanticKey(),
                                "executionIdentity", selector)));
            }
        }
        return List.copyOf(tasks);
    }

    @Override
    public ExecutionResult execute(ScenarioTask task, ExecutionContext context) throws Exception {
        Selector selector = Selector.parse(task.selector());
        Class<?> runnerClass = Class.forName(selector.runnerClass(), false, context.classLoader());
        return jsonReportIsolation.execute(
                task, context, runnerClass,
                () -> executeSelected(task, context, selector, runnerClass));
    }

    private ExecutionResult executeSelected(
            ScenarioTask task,
            ExecutionContext context,
            Selector selector,
            Class<?> runnerClass) {
        Request base = Request.aClass(runnerClass);
        Description root = base.getRunner().getDescription();
        Description selected = resolveSelectedDescription(root, selector);
        if (selected == null) {
            Instant now = Instant.now();
            return new ExecutionResult(task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                    Duration.ZERO, context.workerId(), context.attempt(), now, now,
                    "Could not safely resolve JUnit 4 scenario selector " + task.selector()
                            + "; the runner description tree changed or the semantic identity became ambiguous",
                    "SelectionFailure");
        }

        Instant started = Instant.now();
        Result result = new JUnitCore().run(base.filterWith(selected));
        Instant finished = Instant.now();
        Duration duration = Duration.between(started, finished);

        if (result.getFailureCount() > 0) {
            Throwable failure = result.getFailures().isEmpty() ? null : result.getFailures().get(0).getException();
            return new ExecutionResult(task.id(), task.displayName(), ResultStatus.TEST_FAILURE,
                    duration, context.workerId(), context.attempt(), started, finished,
                    failure == null ? result.getFailures().toString() : safeMessage(failure),
                    failure == null ? null : failure.getClass().getName());
        }

        int assumptions = result.getAssumptionFailureCount();
        int ignored = result.getIgnoreCount();
        if (assumptions > 0 || ignored > 0) {
            return new ExecutionResult(task.id(), task.displayName(), ResultStatus.SKIPPED,
                    duration, context.workerId(), context.attempt(), started, finished,
                    assumptions > 0
                            ? "JUnit 4 skipped the selected scenario because an assumption failed"
                            : "JUnit 4 ignored the selected scenario",
                    assumptions > 0 ? "JUnit4AssumptionSkipped" : "JUnit4Ignored");
        }

        if (result.getRunCount() != 1) {
            return new ExecutionResult(task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                    duration, context.workerId(), context.attempt(), started, finished,
                    "JUnit 4 selected scenario produced " + result.getRunCount()
                            + " executed tests; ScenarioMesh requires exactly one terminal execution per task",
                    "SelectionMultiplicityFailure");
        }

        return new ExecutionResult(task.id(), task.displayName(), ResultStatus.PASSED,
                duration, context.workerId(), context.attempt(), started, finished, null, null);
    }

    private Description resolveSelectedDescription(Description root, Selector selector) {
        Description atPath = descriptionAtPath(root, selector.path());
        if (selector.semanticKey() == null) return atPath; // Backward-compatible selectors.
        if (atPath != null) {
            List<JUnit4DescriptionLeaves.Leaf> leaves = descriptionLeaves.collect(root);
            for (JUnit4DescriptionLeaves.Leaf leaf : leaves) {
                if (leaf.description() == atPath && selector.semanticKey().equals(leaf.semanticKey())) {
                    return atPath;
                }
            }
        }

        List<JUnit4DescriptionLeaves.Leaf> semanticMatches = descriptionLeaves.collect(root).stream()
                .filter(leaf -> selector.semanticKey().equals(leaf.semanticKey()))
                .toList();
        return semanticMatches.size() == 1 ? semanticMatches.get(0).description() : null;
    }

    private List<Class<?>> findCucumberRunnerClasses(AdapterContext context) throws IOException {
        List<Class<?>> runners = new ArrayList<>();
        List<String> inspectionFailures = new ArrayList<>();
        List<String> unsupportedJUnitOwners = new ArrayList<>();

        for (Path root : context.testRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(path -> path.toString().endsWith(".class"))
                        .filter(path -> !path.getFileName().toString().contains("$"))
                        .sorted(Comparator.naturalOrder()).toList()) {
                    String name = root.relativize(file).toString()
                            .replace('/', '.').replace('\\', '.').replaceAll("\\.class$", "");
                    if (!context.discoverySelection().matchesClassName(name)) continue;
                    try {
                        Class<?> candidate = Class.forName(name, false, context.classLoader());
                        RunWith runWith = candidate.getAnnotation(RunWith.class);
                        if (runWith != null && CUCUMBER_RUNNERS.contains(runWith.value().getName())) {
                            runners.add(candidate);
                        } else if (ownsGenericJUnitExecution(candidate, runWith)) {
                            unsupportedJUnitOwners.add(candidate.getName());
                        }
                    } catch (LinkageError | ClassNotFoundException | RuntimeException exception) {
                        inspectionFailures.add(name + " -> " + safeMessage(exception));
                    }
                }
            }
        }

        if (!inspectionFailures.isEmpty()) {
            throw new IllegalStateException(
                    "Cucumber JUnit 4 discovery could not safely inspect selected candidate class(es): "
                            + String.join("; ", inspectionFailures));
        }
        if (!unsupportedJUnitOwners.isEmpty()) {
            throw new IllegalStateException(
                    "Cucumber JUnit 4 takeover found ordinary JUnit-owned test class(es) that this adapter cannot execute: "
                            + String.join(", ", unsupportedJUnitOwners)
                            + ". ScenarioMesh will not silently omit them.");
        }
        return runners;
    }

    private boolean ownsGenericJUnitExecution(Class<?> candidate, RunWith runWith) {
        if (runWith != null) return true;
        if (TestCase.class.isAssignableFrom(candidate)) return true;
        for (Method method : candidate.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Test.class)) return true;
        }
        return false;
    }

    private Description descriptionAtPath(Description root, List<Integer> path) {
        Description current = root;
        for (Integer index : path) {
            List<Description> children = current.getChildren();
            if (index < 0 || index >= children.size()) return null;
            current = children.get(index);
        }
        return current;
    }

    private boolean present(ClassLoader classLoader, String name) {
        try {
            Class.forName(name, false, classLoader);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getName() : message;
    }

    private record Selector(String runnerClass, List<Integer> path, String semanticKey) {
        String encode() {
            String pathValue = path.stream().map(String::valueOf)
                    .reduce((left, right) -> left + "." + right).orElse("");
            String semantic = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(semanticKey.getBytes(StandardCharsets.UTF_8));
            return runnerClass + "#" + pathValue + "#" + semantic;
        }

        static Selector parse(String value) {
            String[] parts = value.split("#", 3);
            if (parts.length < 2) throw new IllegalArgumentException("Invalid JUnit 4 selector: " + value);
            List<Integer> path = parts[1].isBlank()
                    ? List.of()
                    : Stream.of(parts[1].split("\\.")).map(Integer::parseInt).toList();
            String semantic = parts.length == 3
                    ? new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8)
                    : null;
            return new Selector(parts[0], path, semantic);
        }
    }
}
