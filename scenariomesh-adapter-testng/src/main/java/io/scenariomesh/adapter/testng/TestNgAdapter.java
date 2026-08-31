package io.scenariomesh.adapter.testng;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import io.scenariomesh.core.Ports.WorkUnitExecution;
import io.scenariomesh.core.ScenarioIds;
import io.scenariomesh.core.SelectedTestClasses;
import io.scenariomesh.core.TaskMetadata;
import io.scenariomesh.maven.selection.SurefireGroupSelection;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * TestNG adapter.
 *
 * <p>ScenarioMesh only splits TestNG tests into independent method work units when that split is
 * provably semantics preserving. Advanced TestNG features are deliberately delegated to TestNG
 * itself in one runtime materializer scope, then converted into concrete ScenarioMesh results.
 * This keeps TestNG as the source of truth for factories, data providers, dependency ordering,
 * repeated invocations, parameter injection and lifecycle callbacks.</p>
 */
public final class TestNgAdapter implements ScenarioAdapter {
    public static final String ID = "testng";
    private static final String SUITE_XML_FILES_PROPERTY = "scenariomesh.testng.suiteXmlFiles";
    private static final String CLASS_NAMES = "testngClassNames";
    private static final String MATERIALIZER_KIND = "testngMaterializerKind";
    private static final String MATERIALIZER_SUITE = "suite";
    private static final String MATERIALIZER_NATIVE_SCOPE = "native-scope";
    private static final Set<String> CONTEXT_LIFECYCLE_ANNOTATIONS = Set.of(
            "org.testng.annotations.BeforeSuite", "org.testng.annotations.AfterSuite",
            "org.testng.annotations.BeforeTest", "org.testng.annotations.AfterTest",
            "org.testng.annotations.BeforeClass", "org.testng.annotations.AfterClass",
            "org.testng.annotations.BeforeGroups", "org.testng.annotations.AfterGroups");

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
                    .map(String::trim).filter(value -> !value.isEmpty()).distinct()
                    .map(this::suiteTask).toList();
        }

        List<Class<?>> candidates = new ArrayList<>();
        List<String> inspectionFailures = new ArrayList<>();
        for (String className : SelectedTestClasses.scan(context.testRoots(), context.discoverySelection())) {
            if (className.contains("$")) continue;
            try {
                Class<?> candidate = Class.forName(className, false, context.classLoader());
                if (hasTestNgTests(candidate)) candidates.add(candidate);
            } catch (LinkageError | ClassNotFoundException | RuntimeException exception) {
                inspectionFailures.add(className + " -> " + message(exception));
            }
        }
        if (!inspectionFailures.isEmpty()) {
            throw new IllegalStateException("TestNG discovery could not safely inspect selected candidate class(es): "
                    + String.join("; ", inspectionFailures));
        }
        if (candidates.isEmpty()) return List.of();

        GroupSelection groupSelection = GroupSelection.from(context.properties());
        if (candidates.stream().anyMatch(this::requiresNativeScope)) {
            return List.of(nativeScopeTask(candidates));
        }

        List<ScenarioTask> tasks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Class<?> candidate : candidates) discoverIndependentMethods(candidate, tasks, seen, groupSelection);
        return List.copyOf(tasks);
    }

    private boolean hasTestNgTests(Class<?> candidate) {
        if (candidate.isAnnotationPresent(Test.class)) return true;
        for (Method method : candidate.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Test.class) || method.isAnnotationPresent(Factory.class)) return true;
        }
        for (Constructor<?> constructor : candidate.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(Factory.class)) return true;
        }
        return false;
    }

    private boolean requiresNativeScope(Class<?> candidate) {
        if (candidate.isAnnotationPresent(Test.class)) return true;
        for (Class<?> type = candidate; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (constructor.isAnnotationPresent(Factory.class) || constructor.isAnnotationPresent(Parameters.class)) return true;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Factory.class) || method.isAnnotationPresent(Parameters.class)) return true;
                if (hasContextLifecycle(method)) return true;
                Test test = method.getAnnotation(Test.class);
                if (test == null) continue;
                if (!test.dataProvider().isBlank() || test.invocationCount() != 1
                        || test.dependsOnMethods().length > 0 || test.dependsOnGroups().length > 0) return true;
            }
        }
        return false;
    }

    private boolean hasContextLifecycle(Method method) {
        for (Annotation annotation : method.getDeclaredAnnotations()) {
            if (CONTEXT_LIFECYCLE_ANNOTATIONS.contains(annotation.annotationType().getName())) return true;
        }
        return false;
    }

    private void discoverIndependentMethods(Class<?> candidate, List<ScenarioTask> tasks, Set<String> seen,
                                            GroupSelection groupSelection) {
        for (Method method : candidate.getDeclaredMethods()) {
            Test annotation = method.getAnnotation(Test.class);
            if (annotation == null || !groupSelection.includes(annotation.groups())) continue;
            String selector = candidate.getName() + "#" + method.toGenericString();
            if (!seen.add(selector)) continue;
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("className", candidate.getName());
            metadata.put("methodName", method.getName());
            metadata.put("enabled", Boolean.toString(annotation.enabled()));
            metadata.put(TaskMetadata.EXECUTION_SCOPE_ID, "testng-class:" + candidate.getName());
            metadata.put(TaskMetadata.EXECUTION_SCOPE_KIND, "testng-class");
            tasks.add(new ScenarioTask(ScenarioIds.from(ID, selector), candidate.getName() + "." + method.getName(),
                    ID, framework(), null, null, selector, Set.of(annotation.groups()), Map.copyOf(metadata)));
        }
    }

    private ScenarioTask suiteTask(String suiteXmlFile) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("suiteXmlFile", suiteXmlFile);
        metadata.put(MATERIALIZER_KIND, MATERIALIZER_SUITE);
        metadata.put(TaskMetadata.RUNTIME_MATERIALIZER, "true");
        metadata.put(TaskMetadata.EXECUTION_SCOPE_ID, "testng-suite:" + suiteXmlFile);
        metadata.put(TaskMetadata.EXECUTION_SCOPE_KIND, "testng-suite");
        return new ScenarioTask(ScenarioIds.from(ID, "suite:" + suiteXmlFile), "TestNG suite " + suiteXmlFile,
                ID, framework(), null, null, "suite:" + suiteXmlFile, Set.of(), Map.copyOf(metadata));
    }

    private ScenarioTask nativeScopeTask(List<Class<?>> classes) {
        List<String> names = classes.stream().map(Class::getName).distinct().sorted().toList();
        String identity = String.join("\n", names);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(CLASS_NAMES, identity);
        metadata.put(MATERIALIZER_KIND, MATERIALIZER_NATIVE_SCOPE);
        metadata.put(TaskMetadata.RUNTIME_MATERIALIZER, "true");
        metadata.put(TaskMetadata.EXECUTION_SCOPE_ID, "testng-native:" + Integer.toUnsignedString(identity.hashCode(), 36));
        metadata.put(TaskMetadata.EXECUTION_SCOPE_KIND, "testng-native");
        return new ScenarioTask(ScenarioIds.from(ID, "native:" + identity),
                "TestNG native execution scope (" + names.size() + " classes)", ID, framework(), null, null,
                "native:" + Integer.toUnsignedString(identity.hashCode(), 36), Set.of(), Map.copyOf(metadata));
    }

    @Override
    public ExecutionResult execute(ScenarioTask task, ExecutionContext context) throws Exception {
        if (isRuntimeMaterializer(task)) {
            WorkUnitExecution execution = executeMaterializer(task, context);
            if (execution.results().size() != 1) {
                throw new IllegalStateException("TestNG runtime materializers must be executed through executeWorkUnit");
            }
            return execution.results().get(0);
        }
        Instant started = Instant.now();
        if ("false".equalsIgnoreCase(task.metadata().get("enabled"))) {
            Instant finished = Instant.now();
            return new ExecutionResult(task.id(), task.displayName(), ResultStatus.SKIPPED,
                    Duration.between(started, finished), context.workerId(), context.attempt(), started, finished,
                    "TestNG test is disabled", "TestNGDisabled");
        }

        String className = task.metadata().get("className");
        String generic = task.selector().substring(task.selector().indexOf('#') + 1);
        Class<?> clazz = Class.forName(className, false, context.classLoader());
        TestNG testNg = baseTestNg();
        testNg.setTestClasses(new Class<?>[]{clazz});
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
        if (tasks.size() == 1 && isRuntimeMaterializer(tasks.get(0))) return executeMaterializer(tasks.get(0), context);
        return ScenarioAdapter.super.executeWorkUnit(tasks, context);
    }

    private boolean isRuntimeMaterializer(ScenarioTask task) {
        return Boolean.parseBoolean(task.metadata().getOrDefault(TaskMetadata.RUNTIME_MATERIALIZER, "false"));
    }

    private WorkUnitExecution executeMaterializer(ScenarioTask parent, ExecutionContext context) throws Exception {
        return MATERIALIZER_SUITE.equals(parent.metadata().get(MATERIALIZER_KIND))
                ? executeSuite(parent, context) : executeNativeScope(parent, context);
    }

    private WorkUnitExecution executeSuite(ScenarioTask parent, ExecutionContext context) {
        TestNG testNg = baseTestNg();
        testNg.setTestSuites(List.of(parent.metadata().get("suiteXmlFile")));
        applyTestNgSuiteGroupSelection(testNg, context.properties());
        return runAndMaterialize(parent, context, testNg, "testng-suite");
    }

    private WorkUnitExecution executeNativeScope(ScenarioTask parent, ExecutionContext context) throws Exception {
        List<Class<?>> classes = new ArrayList<>();
        for (String name : parent.metadata().getOrDefault(CLASS_NAMES, "").split("\\R")) {
            if (!name.isBlank()) classes.add(Class.forName(name.trim(), false, context.classLoader()));
        }
        if (classes.isEmpty()) throw new IllegalStateException("TestNG native materializer has no selected classes");
        TestNG testNg = baseTestNg();
        testNg.setTestClasses(classes.toArray(Class<?>[]::new));
        // Once advanced semantics are present, TestNG itself must own group filtering so dependency,
        // factory, lifecycle and group-before/after behavior is computed from one native graph.
        applyTestNgSuiteGroupSelection(testNg, context.properties());
        return runAndMaterialize(parent, context, testNg, "testng-native");
    }

    private TestNG baseTestNg() {
        TestNG testNg = new TestNG(false);
        testNg.setUseDefaultListeners(false);
        testNg.setVerbose(0);
        return testNg;
    }

    private WorkUnitExecution runAndMaterialize(ScenarioTask parent, ExecutionContext context, TestNG testNg,
                                                String scopeKind) {
        SuiteCapturingListener listener = new SuiteCapturingListener();
        testNg.addListener((ITestListener) listener);
        testNg.addListener((IConfigurationListener) listener);
        testNg.run();

        List<ScenarioTask> concreteTasks = new ArrayList<>();
        List<ExecutionResult> concreteResults = new ArrayList<>();
        int index = 0;
        for (ITestResult outcome : listener.outcomes) {
            String className = outcome.getTestClass() == null ? "unknown" : outcome.getTestClass().getName();
            String methodName = outcome.getMethod() == null ? "unknown" : outcome.getMethod().getMethodName();
            String selector = parent.selector() + "/" + className + "/" + methodName + "/" + index++;
            Map<String, String> metadata = childMetadata(parent, scopeKind);
            metadata.put("className", className);
            metadata.put("methodName", methodName);
            Object instance = outcome.getInstance();
            if (instance != null) metadata.put("testngInstanceIdentity", Integer.toHexString(System.identityHashCode(instance)));
            metadata.put("testngInvocationIndex", Integer.toString(index - 1));
            ScenarioTask concrete = new ScenarioTask(ScenarioIds.from(ID, selector), className + "." + methodName,
                    ID, framework(), null, null, selector,
                    Set.of(outcome.getMethod() == null ? new String[0] : outcome.getMethod().getGroups()),
                    Map.copyOf(metadata));
            concreteTasks.add(concrete);
            concreteResults.add(suiteResult(concrete, outcome, context));
        }
        for (ITestResult failure : listener.configurationFailures) {
            String methodName = failure.getMethod() == null ? "configuration" : failure.getMethod().getMethodName();
            String selector = parent.selector() + "/configuration/" + methodName + "/" + index++;
            ScenarioTask concrete = new ScenarioTask(ScenarioIds.from(ID, selector), "TestNG configuration " + methodName,
                    ID, framework(), null, null, selector, Set.of(), Map.copyOf(childMetadata(parent, scopeKind)));
            Throwable throwable = failure.getThrowable();
            Instant started = Instant.ofEpochMilli(Math.max(0L, failure.getStartMillis()));
            Instant finished = Instant.ofEpochMilli(Math.max(failure.getStartMillis(), failure.getEndMillis()));
            concreteTasks.add(concrete);
            concreteResults.add(new ExecutionResult(concrete.id(), concrete.displayName(), ResultStatus.TEST_FAILURE,
                    Duration.between(started, finished), context.workerId(), context.attempt(), started, finished,
                    throwable == null ? "TestNG configuration failed" : message(throwable),
                    throwable == null ? "TestNGConfigurationFailure" : throwable.getClass().getName()));
        }
        if (concreteTasks.isEmpty()) {
            Instant now = Instant.now();
            String detail = listener.configurationFailures.isEmpty()
                    ? "TestNG execution scope produced no test outcomes"
                    : "TestNG configuration failed: " + message(listener.configurationFailures.get(0).getThrowable());
            return new WorkUnitExecution(List.of(parent), List.of(new ExecutionResult(parent.id(), parent.displayName(),
                    ResultStatus.INFRASTRUCTURE_FAILURE, Duration.ZERO, context.workerId(), context.attempt(), now, now,
                    detail, "TestNgMaterializerExecutionFailure")));
        }
        return new WorkUnitExecution(concreteTasks, concreteResults);
    }

    private Map<String, String> childMetadata(ScenarioTask parent, String scopeKind) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(TaskMetadata.PARENT_MATERIALIZER_ID, parent.id().value());
        metadata.put(TaskMetadata.PARENT_MATERIALIZER_SELECTOR, parent.selector());
        metadata.put(TaskMetadata.EXECUTION_SCOPE_ID, parent.metadata().get(TaskMetadata.EXECUTION_SCOPE_ID));
        metadata.put(TaskMetadata.EXECUTION_SCOPE_KIND, scopeKind);
        return metadata;
    }

    private ExecutionResult suiteResult(ScenarioTask task, ITestResult outcome, ExecutionContext context) {
        Instant started = Instant.ofEpochMilli(Math.max(0L, outcome.getStartMillis()));
        Instant finished = Instant.ofEpochMilli(Math.max(outcome.getStartMillis(), outcome.getEndMillis()));
        Throwable failure = outcome.getThrowable();
        ResultStatus status;
        String detail = null;
        String type = null;
        if (outcome.getStatus() == ITestResult.SUCCESS) status = ResultStatus.PASSED;
        else if (outcome.getStatus() == ITestResult.SKIP) {
            status = ResultStatus.SKIPPED;
            detail = failure == null ? "TestNG skipped the selected test" : message(failure);
            type = failure == null ? "TestNGSkipped" : failure.getClass().getName();
        } else {
            status = ResultStatus.TEST_FAILURE;
            detail = failure == null ? "TestNG test failed" : message(failure);
            type = failure == null ? null : failure.getClass().getName();
        }
        return new ExecutionResult(task.id(), task.displayName(), status, Duration.between(started, finished),
                context.workerId(), context.attempt(), started, finished, detail, type);
    }

    private ExecutionResult classify(ScenarioTask task, ExecutionContext context, Instant started, Instant finished,
                                     CapturingListener listener) {
        Duration duration = Duration.between(started, finished);
        if (listener.configurationFailure != null) return testFailure(task, context, started, finished, duration,
                listener.configurationFailure, "TestNG configuration failed");
        if (listener.failures > 0) return testFailure(task, context, started, finished, duration,
                listener.failure, "TestNG test failed");
        if (listener.skipped > 0) {
            String detail = listener.skipCause == null ? "TestNG skipped the selected test"
                    : "TestNG skipped the selected test: " + message(listener.skipCause);
            return new ExecutionResult(task.id(), task.displayName(), ResultStatus.SKIPPED, duration,
                    context.workerId(), context.attempt(), started, finished, detail,
                    listener.skipCause == null ? "TestNGSkipped" : listener.skipCause.getClass().getName());
        }
        if (listener.successes == 1) return new ExecutionResult(task.id(), task.displayName(), ResultStatus.PASSED,
                duration, context.workerId(), context.attempt(), started, finished, null, null);
        if (listener.successes > 1) return new ExecutionResult(task.id(), task.displayName(),
                ResultStatus.INFRASTRUCTURE_FAILURE, duration, context.workerId(), context.attempt(), started, finished,
                "TestNG selected method produced " + listener.successes + " successful invocations", "SelectionMultiplicityFailure");
        return new ExecutionResult(task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE, duration,
                context.workerId(), context.attempt(), started, finished,
                "TestNG did not execute selected method " + task.displayName(), "SelectionFailure");
    }

    private ExecutionResult testFailure(ScenarioTask task, ExecutionContext context, Instant started, Instant finished,
                                        Duration duration, Throwable failure, String defaultMessage) {
        return new ExecutionResult(task.id(), task.displayName(), ResultStatus.TEST_FAILURE, duration,
                context.workerId(), context.attempt(), started, finished,
                failure == null ? defaultMessage : message(failure), failure == null ? null : failure.getClass().getName());
    }

    private static String message(Throwable throwable) {
        if (throwable == null) return "unknown failure";
        String detail = throwable.getMessage();
        return detail == null || detail.isBlank() ? throwable.getClass().getName() : detail;
    }

    private static void applyTestNgSuiteGroupSelection(TestNG testNg, Map<String, String> properties) {
        String groups = properties.get("groups");
        String excludedGroups = properties.get("excludedGroups");
        if (groups != null && !groups.isEmpty()) testNg.setGroups(groups);
        if (excludedGroups != null && !excludedGroups.isEmpty()) testNg.setExcludedGroups(excludedGroups);
    }

    private record GroupSelection(SurefireGroupSelection selection) {
        private static GroupSelection from(Map<String, String> properties) {
            return new GroupSelection(SurefireGroupSelection.fromExpressions(
                    properties.get("groups"), properties.get("excludedGroups")));
        }
        private boolean includes(String[] groups) { return selection.matches(groups); }
    }

    private static final class ExactMethodInterceptor implements IMethodInterceptor {
        private final String generic;
        private ExactMethodInterceptor(String generic) { this.generic = generic; }
        @Override public List<IMethodInstance> intercept(List<IMethodInstance> methods, ITestContext context) {
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
        @Override public void onTestFailure(ITestResult result) { failures++; if (failure == null) failure = result.getThrowable(); }
        @Override public void onTestSkipped(ITestResult result) { skipped++; if (skipCause == null) skipCause = result.getThrowable(); }
        @Override public void onConfigurationFailure(ITestResult result) { if (configurationFailure == null) configurationFailure = result.getThrowable(); }
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
