package io.scenariomesh.adapter.testng;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import io.scenariomesh.core.Ports.WorkUnitExecution;
import io.scenariomesh.core.ScenarioIds;
import io.scenariomesh.core.TaskMetadata;
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
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class TestNgAdapter implements ScenarioAdapter {
    public static final String ID = "testng";
    private static final String SUITE_XML_FILES_PROPERTY = "scenariomesh.testng.suiteXmlFiles";
    private static final Set<String> UNSAFE_CONFIGURATION_ANNOTATIONS = Set.of(
            "org.testng.annotations.BeforeSuite",
            "org.testng.annotations.AfterSuite",
            "org.testng.annotations.BeforeTest",
            "org.testng.annotations.AfterTest",
            "org.testng.annotations.BeforeClass",
            "org.testng.annotations.AfterClass",
            "org.testng.annotations.BeforeGroups",
            "org.testng.annotations.AfterGroups");

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
        String suiteFiles = context.properties().get(SUITE_XML_FILES_PROPERTY);
        if (suiteFiles != null && !suiteFiles.isBlank()) {
            return Stream.of(suiteFiles.split("\\R"))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .distinct()
                    .map(this::suiteTask)
                    .toList();
        }
        GroupSelection groupSelection = GroupSelection.from(context.properties());
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
                        discoverMethods(candidate, tasks, seen, groupSelection);
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

    private ScenarioTask suiteTask(String suiteXmlFile) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("suiteXmlFile", suiteXmlFile);
        metadata.put(TaskMetadata.RUNTIME_MATERIALIZER, "true");
        metadata.put(TaskMetadata.EXECUTION_SCOPE_ID, "testng-suite:" + suiteXmlFile);
        metadata.put(TaskMetadata.EXECUTION_SCOPE_KIND, "testng-suite");
        return new ScenarioTask(ScenarioIds.from(ID, "suite:" + suiteXmlFile),
                "TestNG suite " + suiteXmlFile, ID, framework(), null, null,
                "suite:" + suiteXmlFile, Set.of(), Map.copyOf(metadata));
    }

    private void discoverMethods(Class<?> candidate, List<ScenarioTask> tasks, Set<String> seen,
                                 GroupSelection groupSelection) {
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
            if (!groupSelection.includes(annotation.groups())) continue;

            String selector = candidate.getName() + "#" + method.toGenericString();
            if (!seen.add(selector)) continue;

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("className", candidate.getName());
            metadata.put("methodName", method.getName());
            metadata.put("enabled", Boolean.toString(annotation.enabled()));
            metadata.put(TaskMetadata.EXECUTION_SCOPE_ID, "testng-class:" + candidate.getName());
            metadata.put(TaskMetadata.EXECUTION_SCOPE_KIND, "testng-class");
            tasks.add(new ScenarioTask(
                    ScenarioIds.from(ID, selector), candidate.getName() + "." + method.getName(),
                    ID, framework(), null, null, selector, Set.of(annotation.groups()), Map.copyOf(metadata)));
        }
    }

    private void rejectUnsupportedClassSemantics(Class<?> candidate) {
        for (Class<?> type = candidate; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (constructor.isAnnotationPresent(Factory.class)) {
                    throw new IllegalStateException(
                            "TestNG @Factory instance multiplicity is not yet supported safely for isolated execution: "
                                    + candidate.getName());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Factory.class)) {
                    throw new IllegalStateException(
                            "TestNG @Factory instance multiplicity is not yet supported safely for isolated execution: "
                                    + candidate.getName() + "." + method.getName());
                }
                String unsafeLifecycle = unsafeConfigurationAnnotation(method);
                if (unsafeLifecycle != null) {
                    throw new IllegalStateException(
                            "TestNG " + unsafeLifecycle
                                    + " lifecycle is not yet supported safely for isolated method execution: "
                                    + candidate.getName() + "." + method.getName());
                }
            }
        }
    }

    private String unsafeConfigurationAnnotation(Method method) {
        for (Annotation annotation : method.getDeclaredAnnotations()) {
            String name = annotation.annotationType().getName();
            if (UNSAFE_CONFIGURATION_ANNOTATIONS.contains(name)) {
                return annotation.annotationType().getSimpleName();
            }
        }
        return null;
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
        if (isSuiteMaterializer(task)) {
            WorkUnitExecution execution = executeSuite(task, context);
            if (execution.results().size() != 1) {
                throw new IllegalStateException("TestNG suite materializers must be executed through executeWorkUnit");
            }
            return execution.results().get(0);
        }
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
        applyGroupSelection(testNg, context.properties());

        testNg.setMethodInterceptor(new ExactMethodInterceptor(generic));
        CapturingListener listener = new CapturingListener();
        testNg.addListener((ITestListener) listener);
        testNg.addListener((IConfigurationListener) listener);
        testNg.run();
        Instant finished = Instant.now();
        return classify(task, context, started, finished, listener);
    }

    @Override
    public WorkUnitExecution executeWorkUnit(List<ScenarioTask> tasks, ExecutionContext context) throws Exception {
        if (tasks.size() == 1 && isSuiteMaterializer(tasks.get(0))) {
            return executeSuite(tasks.get(0), context);
        }
        return ScenarioAdapter.super.executeWorkUnit(tasks, context);
    }

    private boolean isSuiteMaterializer(ScenarioTask task) {
        return Boolean.parseBoolean(task.metadata().getOrDefault(TaskMetadata.RUNTIME_MATERIALIZER, "false"));
    }

    private WorkUnitExecution executeSuite(ScenarioTask parent, ExecutionContext context) {
        String suiteXmlFile = parent.metadata().get("suiteXmlFile");
        TestNG testNg = new TestNG(false);
        testNg.setUseDefaultListeners(false);
        testNg.setVerbose(0);
        testNg.setTestSuites(List.of(suiteXmlFile));
        applyGroupSelection(testNg, context.properties());
        SuiteCapturingListener listener = new SuiteCapturingListener();
        testNg.addListener((ITestListener) listener);
        testNg.addListener((IConfigurationListener) listener);
        testNg.run();

        List<ScenarioTask> concreteTasks = new ArrayList<>();
        List<ExecutionResult> concreteResults = new ArrayList<>();
        int index = 0;
        for (ITestResult outcome : listener.outcomes) {
            String className = outcome.getTestClass() == null
                    ? "unknown" : outcome.getTestClass().getName();
            String methodName = outcome.getMethod() == null
                    ? "unknown" : outcome.getMethod().getMethodName();
            String selector = parent.selector() + "/" + className + "/" + methodName + "/" + index++;
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("className", className);
            metadata.put("methodName", methodName);
            metadata.put(TaskMetadata.PARENT_MATERIALIZER_ID, parent.id().value());
            metadata.put(TaskMetadata.PARENT_MATERIALIZER_SELECTOR, parent.selector());
            metadata.put(TaskMetadata.EXECUTION_SCOPE_ID,
                    parent.metadata().get(TaskMetadata.EXECUTION_SCOPE_ID));
            metadata.put(TaskMetadata.EXECUTION_SCOPE_KIND, "testng-suite");
            ScenarioTask concrete = new ScenarioTask(ScenarioIds.from(ID, selector),
                    className + "." + methodName, ID, framework(), null, null,
                    selector, Set.of(outcome.getMethod() == null ? new String[0] : outcome.getMethod().getGroups()),
                    Map.copyOf(metadata));
            concreteTasks.add(concrete);
            concreteResults.add(suiteResult(concrete, outcome, context));
        }
        for (ITestResult configurationFailure : listener.configurationFailures) {
            String methodName = configurationFailure.getMethod() == null
                    ? "configuration" : configurationFailure.getMethod().getMethodName();
            String selector = parent.selector() + "/configuration/" + methodName + "/" + index++;
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put(TaskMetadata.PARENT_MATERIALIZER_ID, parent.id().value());
            metadata.put(TaskMetadata.PARENT_MATERIALIZER_SELECTOR, parent.selector());
            metadata.put(TaskMetadata.EXECUTION_SCOPE_ID,
                    parent.metadata().get(TaskMetadata.EXECUTION_SCOPE_ID));
            metadata.put(TaskMetadata.EXECUTION_SCOPE_KIND, "testng-suite");
            ScenarioTask concrete = new ScenarioTask(ScenarioIds.from(ID, selector),
                    "TestNG configuration " + methodName, ID, framework(), null, null,
                    selector, Set.of(), Map.copyOf(metadata));
            Throwable failure = configurationFailure.getThrowable();
            Instant started = Instant.ofEpochMilli(Math.max(0L, configurationFailure.getStartMillis()));
            Instant finished = Instant.ofEpochMilli(Math.max(
                    configurationFailure.getStartMillis(), configurationFailure.getEndMillis()));
            concreteTasks.add(concrete);
            concreteResults.add(new ExecutionResult(concrete.id(), concrete.displayName(),
                    ResultStatus.TEST_FAILURE, Duration.between(started, finished),
                    context.workerId(), context.attempt(), started, finished,
                    failure == null ? "TestNG suite configuration failed" : message(failure),
                    failure == null ? "TestNGConfigurationFailure" : failure.getClass().getName()));
        }

        if (concreteTasks.isEmpty()) {
            Instant now = Instant.now();
            String detail = listener.configurationFailures.isEmpty()
                    ? "TestNG suite produced no test outcomes: " + suiteXmlFile
                    : "TestNG suite configuration failed: " + message(listener.configurationFailures.get(0).getThrowable());
            ExecutionResult failure = new ExecutionResult(parent.id(), parent.displayName(),
                    ResultStatus.INFRASTRUCTURE_FAILURE, Duration.ZERO, context.workerId(), context.attempt(),
                    now, now, detail, "TestNgSuiteExecutionFailure");
            return new WorkUnitExecution(List.of(parent), List.of(failure));
        }
        return new WorkUnitExecution(concreteTasks, concreteResults);
    }

    private ExecutionResult suiteResult(ScenarioTask task, ITestResult outcome, ExecutionContext context) {
        Instant started = Instant.ofEpochMilli(Math.max(0L, outcome.getStartMillis()));
        Instant finished = Instant.ofEpochMilli(Math.max(outcome.getStartMillis(), outcome.getEndMillis()));
        ResultStatus status;
        String detail = null;
        String type = null;
        Throwable failure = outcome.getThrowable();
        if (outcome.getStatus() == ITestResult.SUCCESS) {
            status = ResultStatus.PASSED;
        } else if (outcome.getStatus() == ITestResult.SKIP) {
            status = ResultStatus.SKIPPED;
            detail = failure == null ? "TestNG skipped the selected test" : message(failure);
            type = failure == null ? "TestNGSkipped" : failure.getClass().getName();
        } else {
            status = ResultStatus.TEST_FAILURE;
            detail = failure == null ? "TestNG test failed" : message(failure);
            type = failure == null ? null : failure.getClass().getName();
        }
        return new ExecutionResult(task.id(), task.displayName(), status,
                Duration.between(started, finished), context.workerId(), context.attempt(),
                started, finished, detail, type);
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

    private static void applyGroupSelection(TestNG testNg, Map<String, String> properties) {
        String groups = properties.get("groups");
        String excludedGroups = properties.get("excludedGroups");
        if (groups != null && !groups.isEmpty()) testNg.setGroups(groups);
        if (excludedGroups != null && !excludedGroups.isEmpty()) testNg.setExcludedGroups(excludedGroups);
    }

    /**
     * Mirrors TestNG's public group-selection behavior without depending on TestNG internals:
     * comma-separated values are trimmed, each entry is a full Java regular expression, '$' is
     * literal unless already escaped, and excluded groups win over included groups. TestNG method
     * dependencies are rejected above, so no group dependency closure needs to be recreated here.
     */
    private record GroupSelection(List<Pattern> included, List<Pattern> excluded) {
        private static GroupSelection from(Map<String, String> properties) {
            return new GroupSelection(patterns(properties.get("groups")),
                    patterns(properties.get("excludedGroups")));
        }

        private boolean includes(String[] groups) {
            boolean includedByGroup = included.isEmpty() || matches(included, groups);
            return includedByGroup && !matches(excluded, groups);
        }

        private static boolean matches(List<Pattern> patterns, String[] groups) {
            for (String group : groups) {
                for (Pattern pattern : patterns) {
                    if (pattern.matcher(group).matches()) return true;
                }
            }
            return false;
        }

        private static List<Pattern> patterns(String raw) {
            if (raw == null || raw.isEmpty()) return List.of();
            return Arrays.stream(raw.split(",", -1))
                    .map(String::trim)
                    .map(GroupSelection::asTestNgRegexp)
                    .map(Pattern::compile)
                    .toList();
        }

        private static String asTestNgRegexp(String group) {
            return group.contains("\\$") ? group : group.replace("$", "\\$");
        }
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

    private static final class SuiteCapturingListener implements ITestListener, IConfigurationListener {
        private final List<ITestResult> outcomes = new ArrayList<>();
        private final List<ITestResult> configurationFailures = new ArrayList<>();
        @Override public void onTestSuccess(ITestResult result) { outcomes.add(result); }
        @Override public void onTestFailure(ITestResult result) { outcomes.add(result); }
        @Override public void onTestSkipped(ITestResult result) { outcomes.add(result); }
        @Override public void onConfigurationFailure(ITestResult result) { configurationFailures.add(result); }
    }
}
