package io.scenariomesh.adapter.testng;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import io.scenariomesh.core.ScenarioIds;
import org.testng.IConfigurationListener;
import org.testng.IMethodInstance;
import org.testng.IMethodInterceptor;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.TestNG;
import org.testng.annotations.Factory;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class TestNgAdapter implements ScenarioAdapter {
    public static final String ID = "testng";

    @Override public String id() { return ID; }
    @Override public String framework() { return "testng"; }

    @Override
    public boolean isAvailable(ClassLoader classLoader) {
        try {
            Class.forName("org.testng.TestNG", false, classLoader);
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    @Override
    public List<ScenarioTask> discover(AdapterContext context) throws IOException {
        List<ScenarioTask> tasks = new ArrayList<>();
        List<String> inspectionFailures = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Path root : context.testRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream
                        .filter(path -> path.toString().endsWith(".class"))
                        .filter(path -> !path.getFileName().toString().contains("$"))
                        .sorted()
                        .toList()) {
                    String className = root.relativize(file).toString()
                            .replace('/', '.')
                            .replace('\\', '.')
                            .replaceAll("\\.class$", "");
                    if (!context.discoverySelection().matchesClassName(className)) continue;
                    try {
                        Class<?> candidate = Class.forName(className, false, context.classLoader());
                        discoverMethods(candidate, tasks, seen);
                    } catch (LinkageError | ClassNotFoundException | RuntimeException exception) {
                        inspectionFailures.add(className + " -> " + message(exception));
                    }
                }
            }
        }

        if (!inspectionFailures.isEmpty()) {
            throw new IllegalStateException(
                    "TestNG discovery could not safely inspect selected candidate class(es): "
                            + String.join("; ", inspectionFailures));
        }
        return List.copyOf(tasks);
    }

    private void discoverMethods(Class<?> candidate, List<ScenarioTask> tasks, Set<String> seen) {
        rejectUnsupportedClassSemantics(candidate);

        Test classAnnotation = candidate.getAnnotation(Test.class);
        if (classAnnotation != null) {
            throw new IllegalStateException(
                    "TestNG class-level @Test is not yet supported safely for isolated method execution: "
                            + candidate.getName());
        }

        for (Method method : candidate.getDeclaredMethods()) {
            Test annotation = method.getAnnotation(Test.class);
            if (annotation == null) continue;

            rejectUnsupportedMultiplicity(candidate, method, annotation);
            if (annotation.dependsOnMethods().length > 0 || annotation.dependsOnGroups().length > 0) {
                throw new IllegalStateException(
                        "TestNG dependency ordering is not yet supported safely for isolated method execution: "
                                + candidate.getName() + "." + method.getName());
            }

            String selector = candidate.getName() + "#" + method.toGenericString();
            if (!seen.add(selector)) continue;

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("className", candidate.getName());
            metadata.put("methodName", method.getName());
            metadata.put("enabled", Boolean.toString(annotation.enabled()));
            tasks.add(new ScenarioTask(
                    ScenarioIds.from(ID, selector), candidate.getName() + "." + method.getName(),
                    ID, framework(), null, null, selector, Set.of(annotation.groups()), Map.copyOf(metadata)));
        }
    }

    private void rejectUnsupportedClassSemantics(Class<?> candidate) {
        for (Constructor<?> constructor : candidate.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(Factory.class)) {
                throw new IllegalStateException(
                        "TestNG @Factory instance multiplicity is not yet supported safely for isolated execution: "
                                + candidate.getName());
            }
        }
        for (Method method : candidate.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Factory.class)) {
                throw new IllegalStateException(
                        "TestNG @Factory instance multiplicity is not yet supported safely for isolated execution: "
                                + candidate.getName() + "." + method.getName());
            }
        }
    }

    private void rejectUnsupportedMultiplicity(Class<?> candidate, Method method, Test annotation) {
        String owner = candidate.getName() + "." + method.getName();
        if (!annotation.dataProvider().isBlank()) {
            throw new IllegalStateException(
                    "TestNG data-provider multiplicity is not yet supported safely for isolated execution: " + owner);
        }
        if (annotation.invocationCount() != 1) {
            throw new IllegalStateException(
                    "TestNG invocationCount=" + annotation.invocationCount()
                            + " is not yet supported safely for isolated execution: " + owner);
        }
        if (method.isAnnotationPresent(Parameters.class)) {
            throw new IllegalStateException(
                    "TestNG @Parameters requires suite/context semantics that are not yet reproduced safely: " + owner);
        }
    }

    @Override
    public ExecutionResult execute(ScenarioTask task, ExecutionContext context) throws Exception {
        Instant started = Instant.now();
        if ("false".equalsIgnoreCase(task.metadata().get("enabled"))) {
            Instant finished = Instant.now();
            return new ExecutionResult(task.id(), task.displayName(), ResultStatus.SKIPPED,
                    Duration.between(started, finished), context.workerId(), context.attempt(),
                    started, finished, "TestNG test is disabled", "TestNGDisabled");
        }

        String className = task.metadata().get("className");
        String generic = task.selector().substring(task.selector().indexOf('#') + 1);
        Class<?> clazz = Class.forName(className, false, context.classLoader());
        TestNG testNg = new TestNG(false);
        testNg.setUseDefaultListeners(false);
        testNg.setVerbose(0);
        testNg.setTestClasses(new Class<?>[]{clazz});

        String groups = context.properties().get("groups");
        String excludedGroups = context.properties().get("excludedGroups");
        if (groups != null && !groups.isBlank()) testNg.setGroups(groups);
        if (excludedGroups != null && !excludedGroups.isBlank()) testNg.setExcludedGroups(excludedGroups);

        testNg.setMethodInterceptor(new ExactMethodInterceptor(generic));
        CapturingListener listener = new CapturingListener();
        testNg.addListener((ITestListener) listener);
        testNg.addListener((IConfigurationListener) listener);
        testNg.run();
        Instant finished = Instant.now();
        return classify(task, context, started, finished, listener);
    }

    private ExecutionResult classify(ScenarioTask task, ExecutionContext context,
                                     Instant started, Instant finished, CapturingListener listener) {
        Duration duration = Duration.between(started, finished);
        if (listener.configurationFailure != null) {
            return testFailure(task, context, started, finished, duration,
                    listener.configurationFailure, "TestNG configuration failed");
        }
        if (listener.failures > 0) {
            return testFailure(task, context, started, finished, duration,
                    listener.failure, "TestNG test failed");
        }
        if (listener.skipped > 0) {
            String detail = listener.skipCause == null
                    ? "TestNG skipped the selected test"
                    : "TestNG skipped the selected test: " + message(listener.skipCause);
            return new ExecutionResult(task.id(), task.displayName(), ResultStatus.SKIPPED, duration,
                    context.workerId(), context.attempt(), started, finished,
                    detail, listener.skipCause == null ? "TestNGSkipped" : listener.skipCause.getClass().getName());
        }
        if (listener.successes == 1) {
            return new ExecutionResult(task.id(), task.displayName(), ResultStatus.PASSED, duration,
                    context.workerId(), context.attempt(), started, finished, null, null);
        }
        if (listener.successes > 1) {
            return new ExecutionResult(task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE, duration,
                    context.workerId(), context.attempt(), started, finished,
                    "TestNG selected method produced " + listener.successes
                            + " successful invocations; ScenarioMesh requires exactly one terminal execution per task",
                    "SelectionMultiplicityFailure");
        }
        return new ExecutionResult(task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE, duration,
                context.workerId(), context.attempt(), started, finished,
                "TestNG did not execute selected method " + task.displayName(), "SelectionFailure");
    }

    private ExecutionResult testFailure(ScenarioTask task, ExecutionContext context,
                                        Instant started, Instant finished, Duration duration,
                                        Throwable failure, String defaultMessage) {
        return new ExecutionResult(task.id(), task.displayName(), ResultStatus.TEST_FAILURE, duration,
                context.workerId(), context.attempt(), started, finished,
                failure == null ? defaultMessage : message(failure),
                failure == null ? null : failure.getClass().getName());
    }

    private static String message(Throwable throwable) {
        String detail = throwable.getMessage();
        return detail == null || detail.isBlank() ? throwable.getClass().getName() : detail;
    }

    private static final class ExactMethodInterceptor implements IMethodInterceptor {
        private final String generic;
        private ExactMethodInterceptor(String generic) { this.generic = generic; }
        @Override
        public List<IMethodInstance> intercept(List<IMethodInstance> methods, ITestContext context) {
            return methods.stream().filter(instance -> {
                Method method = instance.getMethod().getConstructorOrMethod().getMethod();
                return method != null && method.toGenericString().equals(generic);
            }).toList();
        }
    }

    private static final class CapturingListener implements ITestListener, IConfigurationListener {
        private int successes;
        private int failures;
        private int skipped;
        private Throwable failure;
        private Throwable skipCause;
        private Throwable configurationFailure;
        @Override public void onTestSuccess(ITestResult result) { successes++; }
        @Override public void onTestFailure(ITestResult result) {
            failures++; if (failure == null) failure = result.getThrowable();
        }
        @Override public void onTestSkipped(ITestResult result) {
            skipped++; if (skipCause == null) skipCause = result.getThrowable();
        }
        @Override public void onConfigurationFailure(ITestResult result) {
            if (configurationFailure == null) configurationFailure = result.getThrowable();
        }
    }
}
