package io.scenariomesh.adapter.junit4;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterCapabilities;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import io.scenariomesh.core.Ports.WorkUnitExecution;
import io.scenariomesh.core.RuntimePropertyNames;
import io.scenariomesh.core.ScenarioIds;
import io.scenariomesh.core.SelectedTestClasses;
import io.scenariomesh.core.TaskMetadata;
import junit.framework.TestCase;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Direct JUnit 4 provider adapter.
 *
 * <p>This adapter is intentionally activated only when Maven explicitly selects a legacy
 * Surefire JUnit 4 provider (for example surefire-junit47). Unlike the JUnit Platform/Vintage
 * adapter, it executes each Maven-selected top-level JUnit class/suite once through JUnitCore and
 * materializes the native RunListener outcomes afterwards. That preserves custom Runner, Suite,
 * JUnit3 compatibility, assumption, ignore and duplicate-occurrence semantics without replaying
 * individual leaves out of their enclosing JUnit 4 lifecycle.</p>
 */
public final class JUnit4DirectAdapter implements ScenarioAdapter {
    public static final String ID = "junit4-direct";
    public static final String PROVIDER_INTENT = "junit4-direct";
    private static final String CLASS_NAME = "className";

    @Override public String id() { return ID; }
    @Override public String framework() { return "junit4-direct"; }

    @Override
    public AdapterCapabilities capabilities() {
        return new AdapterCapabilities(Set.of("junit4-direct"), Set.of(), false);
    }

    @Override
    public boolean isAvailable(ClassLoader classLoader) {
        try {
            Class.forName("org.junit.runner.JUnitCore", false, classLoader);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    @Override
    public List<ScenarioTask> discover(AdapterContext context) throws Exception {
        if (!PROVIDER_INTENT.equals(context.properties().get(RuntimePropertyNames.MAVEN_PROVIDER_INTENT))) {
            return List.of();
        }
        rejectUnsupportedProviderFilters(context.properties());

        List<ScenarioTask> tasks = new ArrayList<>();
        List<String> inspectionFailures = new ArrayList<>();
        for (String className : SelectedTestClasses.scan(context.testRoots(), context.discoverySelection())) {
            try {
                Class<?> candidate = Class.forName(className, false, context.classLoader());
                if (!isJUnit4Candidate(candidate)) continue;
                String selector = "class:" + className;
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put(CLASS_NAME, className);
                metadata.put(TaskMetadata.RUNTIME_MATERIALIZER, "true");
                metadata.put(TaskMetadata.EXECUTION_SCOPE_ID, "junit4-class:" + className);
                metadata.put(TaskMetadata.EXECUTION_SCOPE_KIND, "junit4-class-or-suite");
                tasks.add(new ScenarioTask(
                        ScenarioIds.from(ID, selector), className, ID, framework(),
                        null, null, selector, Set.of(), Map.copyOf(metadata)));
            } catch (ClassNotFoundException | LinkageError | RuntimeException failure) {
                inspectionFailures.add(className + " -> " + message(failure));
            }
        }
        if (!inspectionFailures.isEmpty()) {
            throw new IllegalStateException("Direct JUnit 4 discovery could not safely inspect selected class(es): "
                    + String.join("; ", inspectionFailures));
        }
        return List.copyOf(tasks);
    }

    private void rejectUnsupportedProviderFilters(Map<String, String> properties) {
        for (String key : List.of("groups", "excludedGroups")) {
            String value = properties.get(key);
            if (value != null && !value.isBlank()) {
                throw new IllegalStateException("Direct JUnit 4 ownership does not yet reproduce Surefire '" + key
                        + "' category filtering; native Maven execution is safer");
            }
        }
    }

    private boolean isJUnit4Candidate(Class<?> type) {
        if (type.getAnnotation(RunWith.class) != null) return true;
        if (TestCase.class.isAssignableFrom(type)) return true;
        for (Method method : type.getMethods()) {
            if (method.isAnnotationPresent(Test.class)) return true;
        }
        try {
            Method suite = type.getMethod("suite");
            return Modifier.isStatic(suite.getModifiers())
                    && suite.getParameterCount() == 0
                    && junit.framework.Test.class.isAssignableFrom(suite.getReturnType());
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    @Override
    public ExecutionResult execute(ScenarioTask task, ExecutionContext context) throws Exception {
        WorkUnitExecution execution = executeWorkUnit(List.of(task), context);
        if (execution.results().size() != 1) {
            throw new IllegalStateException("Direct JUnit 4 runtime materializers must be executed through executeWorkUnit");
        }
        return execution.results().get(0);
    }

    @Override
    public WorkUnitExecution executeWorkUnit(List<ScenarioTask> tasks, ExecutionContext context) throws Exception {
        if (tasks == null || tasks.size() != 1) {
            throw new IllegalArgumentException("Direct JUnit 4 execution requires exactly one top-level class/suite scope");
        }
        ScenarioTask parent = tasks.get(0);
        if (!Boolean.parseBoolean(parent.metadata().getOrDefault(TaskMetadata.RUNTIME_MATERIALIZER, "false"))) {
            throw new IllegalArgumentException("Direct JUnit 4 execution requires a runtime materializer task");
        }

        String className = parent.metadata().get(CLASS_NAME);
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("Direct JUnit 4 materializer has no className metadata");
        }
        Class<?> testClass = Class.forName(className, false, context.classLoader());

        NativeListener listener = new NativeListener();
        JUnitCore core = new JUnitCore();
        core.addListener(listener);
        Result nativeResult = core.run(Request.aClass(testClass));
        List<NativeOutcome> outcomes = listener.completed();

        int expectedTerminals = nativeResult.getRunCount() + nativeResult.getIgnoreCount();
        if (outcomes.size() != expectedTerminals || outcomes.isEmpty()) {
            Instant now = Instant.now();
            String detail = "Direct JUnit 4 scope produced " + outcomes.size()
                    + " listener terminal outcome(s), while JUnit Result reports run=" + nativeResult.getRunCount()
                    + ", ignored=" + nativeResult.getIgnoreCount()
                    + ", failures=" + nativeResult.getFailureCount()
                    + ", assumptions=" + nativeResult.getAssumptionFailureCount();
            ExecutionResult failure = new ExecutionResult(parent.id(), parent.displayName(),
                    ResultStatus.INFRASTRUCTURE_FAILURE, Duration.ZERO, context.workerId(), context.attempt(),
                    now, now, detail, "JUnit4MaterializerCountMismatch");
            return new WorkUnitExecution(List.of(parent), List.of(failure));
        }

        List<ScenarioTask> concreteTasks = new ArrayList<>(outcomes.size());
        List<ExecutionResult> concreteResults = new ArrayList<>(outcomes.size());
        for (NativeOutcome outcome : outcomes) {
            String selector = parent.selector() + "/event-" + outcome.index();
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put(TaskMetadata.PARENT_MATERIALIZER_ID, parent.id().value());
            metadata.put(TaskMetadata.PARENT_MATERIALIZER_SELECTOR, parent.selector());
            metadata.put(TaskMetadata.EXECUTION_SCOPE_ID,
                    parent.metadata().getOrDefault(TaskMetadata.EXECUTION_SCOPE_ID, parent.selector()));
            metadata.put(TaskMetadata.EXECUTION_SCOPE_KIND, "junit4-class-or-suite");
            if (outcome.className() != null) metadata.put(CLASS_NAME, outcome.className());
            if (outcome.methodName() != null) metadata.put("methodName", outcome.methodName());

            ScenarioTask concrete = new ScenarioTask(
                    ScenarioIds.from(ID, selector), outcome.displayName(), ID, framework(),
                    null, null, selector, Set.of(), Map.copyOf(metadata));
            concreteTasks.add(concrete);
            concreteResults.add(new ExecutionResult(
                    concrete.id(), concrete.displayName(), outcome.status(),
                    Duration.between(outcome.started(), outcome.finished()),
                    context.workerId(), context.attempt(), outcome.started(), outcome.finished(),
                    outcome.failureMessage(), outcome.failureType()));
        }
        return new WorkUnitExecution(concreteTasks, concreteResults);
    }

    private static final class NativeListener extends RunListener {
        private final IdentityHashMap<Description, ArrayDeque<MutableOutcome>> active = new IdentityHashMap<>();
        private final IdentityHashMap<Description, Boolean> terminalWithoutStart = new IdentityHashMap<>();
        private final List<NativeOutcome> completed = new ArrayList<>();
        private int sequence;

        @Override
        public synchronized void testStarted(Description description) {
            MutableOutcome outcome = new MutableOutcome(sequence++, description, Instant.now());
            active.computeIfAbsent(description, ignored -> new ArrayDeque<>()).addLast(outcome);
        }

        @Override
        public synchronized void testFailure(Failure failure) {
            Description description = failure.getDescription();
            MutableOutcome outcome = active(description);
            Throwable cause = failure.getException();
            if (outcome == null) {
                Instant now = Instant.now();
                completed.add(terminal(sequence++, description, ResultStatus.TEST_FAILURE, now, now,
                        cause == null ? failure.getMessage() : message(cause),
                        cause == null ? "JUnit4Failure" : cause.getClass().getName()));
                terminalWithoutStart.put(description, Boolean.TRUE);
                return;
            }
            outcome.status = ResultStatus.TEST_FAILURE;
            outcome.failureMessage = cause == null ? failure.getMessage() : message(cause);
            outcome.failureType = cause == null ? "JUnit4Failure" : cause.getClass().getName();
        }

        @Override
        public synchronized void testAssumptionFailure(Failure failure) {
            Description description = failure.getDescription();
            MutableOutcome outcome = active(description);
            Throwable cause = failure.getException();
            String detail = cause == null ? failure.getMessage() : message(cause);
            String type = cause == null ? "JUnit4AssumptionSkipped" : cause.getClass().getName();
            if (outcome == null) {
                Instant now = Instant.now();
                completed.add(terminal(sequence++, description, ResultStatus.SKIPPED, now, now, detail, type));
                terminalWithoutStart.put(description, Boolean.TRUE);
                return;
            }
            outcome.status = ResultStatus.SKIPPED;
            outcome.failureMessage = detail;
            outcome.failureType = type;
        }

        @Override
        public synchronized void testIgnored(Description description) {
            Instant now = Instant.now();
            completed.add(terminal(sequence++, description, ResultStatus.SKIPPED, now, now,
                    "JUnit 4 ignored the selected test", "JUnit4Ignored"));
            terminalWithoutStart.put(description, Boolean.TRUE);
        }

        @Override
        public synchronized void testFinished(Description description) {
            MutableOutcome outcome = removeActive(description);
            if (outcome == null) {
                terminalWithoutStart.remove(description);
                return;
            }
            if (outcome.status == null) outcome.status = ResultStatus.PASSED;
            Instant finished = Instant.now();
            completed.add(terminal(outcome.index, outcome.description, outcome.status,
                    outcome.started, finished, outcome.failureMessage, outcome.failureType));
        }

        synchronized List<NativeOutcome> completed() {
            return completed.stream().sorted(java.util.Comparator.comparingInt(NativeOutcome::index)).toList();
        }

        private MutableOutcome active(Description description) {
            ArrayDeque<MutableOutcome> values = active.get(description);
            return values == null ? null : values.peekFirst();
        }

        private MutableOutcome removeActive(Description description) {
            ArrayDeque<MutableOutcome> values = active.get(description);
            if (values == null || values.isEmpty()) return null;
            MutableOutcome value = values.removeFirst();
            if (values.isEmpty()) active.remove(description);
            return value;
        }

        private NativeOutcome terminal(int index, Description description, ResultStatus status,
                                       Instant started, Instant finished, String failureMessage, String failureType) {
            String display = description == null ? "JUnit 4 execution failure" : description.getDisplayName();
            String className = description == null ? null : description.getClassName();
            String methodName = description == null ? null : description.getMethodName();
            return new NativeOutcome(index, display, className, methodName, status,
                    started, finished, failureMessage, failureType);
        }
    }

    private static final class MutableOutcome {
        private final int index;
        private final Description description;
        private final Instant started;
        private ResultStatus status;
        private String failureMessage;
        private String failureType;

        private MutableOutcome(int index, Description description, Instant started) {
            this.index = index;
            this.description = description;
            this.started = started;
        }
    }

    private record NativeOutcome(int index, String displayName, String className, String methodName,
                                 ResultStatus status, Instant started, Instant finished,
                                 String failureMessage, String failureType) {}

    private static String message(Throwable throwable) {
        if (throwable == null) return "unknown failure";
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getName() : value;
    }
}
