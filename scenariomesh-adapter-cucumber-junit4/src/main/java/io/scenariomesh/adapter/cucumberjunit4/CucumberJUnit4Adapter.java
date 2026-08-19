package io.scenariomesh.adapter.cucumberjunit4;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import io.scenariomesh.core.ScenarioIds;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
                // Correctness identity follows the native executable selector that Maven/JUnit would run.
                // Human-readable Cucumber names are diagnostics only: generated Scenario Outline rows can
                // legitimately have identical feature/scenario names while being owned by different runner
                // classes and generated feature resources.
                String selector = new Selector(runner.getName(), leaf.selectorPath()).encode();
                Description description = leaf.description();
                tasks.add(new ScenarioTask(
                        ScenarioIds.from(ID, selector), description.getDisplayName(), ID, framework(),
                        null, null, selector, Set.of(),
                        Map.of(
                                "runnerClass", runner.getName(),
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
        Request base = Request.aClass(runnerClass);
        Description selected = descriptionAtPath(base.getRunner().getDescription(), selector.path());
        if (selected == null) {
            Instant now = Instant.now();
            return new ExecutionResult(task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                    Duration.ZERO, context.workerId(), context.attempt(), now, now,
                    "Could not resolve JUnit 4 scenario selector " + task.selector(), "SelectionFailure");
        }

        Instant started = Instant.now();
        Result result = new JUnitCore().run(base.filterWith(selected));
        Instant finished = Instant.now();
        if (!result.wasSuccessful()) {
            Throwable failure = result.getFailures().isEmpty() ? null : result.getFailures().get(0).getException();
            return new ExecutionResult(task.id(), task.displayName(), ResultStatus.TEST_FAILURE,
                    Duration.between(started, finished), context.workerId(), context.attempt(), started, finished,
                    failure == null ? result.getFailures().toString() : failure.getMessage(),
                    failure == null ? null : failure.getClass().getName());
        }
        if (result.getRunCount() == 0) {
            return new ExecutionResult(task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                    Duration.between(started, finished), context.workerId(), context.attempt(), started, finished,
                    "JUnit 4 selected scenario produced zero executed tests", "SelectionFailure");
        }
        return new ExecutionResult(task.id(), task.displayName(), ResultStatus.PASSED,
                Duration.between(started, finished), context.workerId(), context.attempt(), started, finished, null, null);
    }

    private List<Class<?>> findCucumberRunnerClasses(AdapterContext context) throws IOException {
        List<Class<?>> result = new ArrayList<>();
        for (Path root : context.testRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(path -> path.toString().endsWith(".class"))
                        .filter(path -> !path.getFileName().toString().contains("$"))
                        .sorted(Comparator.naturalOrder()).toList()) {
                    String name = root.relativize(file).toString()
                            .replace('/', '.').replace('\\', '.').replaceAll("\\.class$", "");
                    if (!context.discoverySelection().matchesClassName(name)) {
                        continue;
                    }
                    try {
                        Class<?> candidate = Class.forName(name, false, context.classLoader());
                        RunWith runWith = candidate.getAnnotation(RunWith.class);
                        if (runWith != null && CUCUMBER_RUNNERS.contains(runWith.value().getName())) {
                            result.add(candidate);
                        }
                    } catch (LinkageError | ClassNotFoundException ignored) {
                        // A non-loadable test class is not a Cucumber runner candidate.
                    }
                }
            }
        }
        return result;
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
        try { Class.forName(name, false, classLoader); return true; }
        catch (ClassNotFoundException ignored) { return false; }
    }

    private record Selector(String runnerClass, List<Integer> path) {
        String encode() {
            return runnerClass + "#" + path.stream().map(String::valueOf)
                    .reduce((left, right) -> left + "." + right).orElse("");
        }
        static Selector parse(String value) {
            int separator = value.indexOf('#');
            if (separator < 0) throw new IllegalArgumentException("Invalid JUnit 4 selector: " + value);
            String runner = value.substring(0, separator);
            String pathText = value.substring(separator + 1);
            if (pathText.isBlank()) return new Selector(runner, List.of());
            return new Selector(runner, Stream.of(pathText.split("\\.")).map(Integer::parseInt).toList());
        }
    }
}
