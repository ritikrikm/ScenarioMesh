package io.scenariomesh.core;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class Ports {
    private Ports() {}

    /**
     * Atomic worker-side execution output. Most adapters return the dispatched tasks
     * unchanged. Engines that materialize executable children at runtime (for example
     * JUnit parameterized or dynamic tests) may replace a materializer placeholder with
     * the concrete child tasks that actually executed.
     */
    public record WorkUnitExecution(List<ScenarioTask> tasks, List<ExecutionResult> results) {
        public WorkUnitExecution {
            tasks = List.copyOf(tasks == null ? List.of() : tasks);
            results = List.copyOf(results == null ? List.of() : results);
            if (tasks.isEmpty()) throw new IllegalArgumentException("WorkUnitExecution requires at least one task");
            if (results.isEmpty()) throw new IllegalArgumentException("WorkUnitExecution requires at least one result");
        }
    }

    public interface ScenarioAdapter {
        String id();
        String framework();
        boolean isAvailable(ClassLoader classLoader);
        List<ScenarioTask> discover(AdapterContext context) throws Exception;
        ExecutionResult execute(ScenarioTask task, ExecutionContext context) throws Exception;

        default List<ExecutionResult> executeBatch(List<ScenarioTask> tasks, ExecutionContext context) throws Exception {
            if (tasks == null || tasks.isEmpty()) {
                throw new IllegalArgumentException("ScenarioAdapter.executeBatch requires at least one task");
            }
            List<ExecutionResult> results = new ArrayList<>(tasks.size());
            for (ScenarioTask task : tasks) results.add(execute(task, context));
            return List.copyOf(results);
        }

        /**
         * Product-level work-unit contract. Runtime-materializing engines override this
         * method so the worker can return the concrete tasks that came into existence
         * during execution. Existing leaf-oriented adapters inherit exact prior behavior.
         */
        default WorkUnitExecution executeWorkUnit(List<ScenarioTask> tasks, ExecutionContext context) throws Exception {
            return new WorkUnitExecution(tasks, executeBatch(tasks, context));
        }
    }

    public record AdapterContext(ClassLoader classLoader,
                                 List<Path> testRoots,
                                 Map<String, String> properties,
                                 DiscoverySelection discoverySelection) {
        public AdapterContext {
            testRoots = List.copyOf(testRoots);
            properties = Map.copyOf(properties);
            discoverySelection = discoverySelection == null ? DiscoverySelection.all() : discoverySelection;
        }
    }

    public record ExecutionContext(ClassLoader classLoader,
                                   WorkerId workerId,
                                   int attempt,
                                   Map<String, String> properties) {
        public ExecutionContext {
            properties = Map.copyOf(properties);
        }
    }

    public interface WorkerTaskCleanup {
        void afterTask(ScenarioTask task, ExecutionContext context, ExecutionResult result) throws Exception;
    }

    public interface SchedulingStrategy {
        void load(Collection<ScenarioTask> tasks);
        ScenarioTask nextEligible(Predicate<ScenarioTask> eligible);
        void requeue(ScenarioTask task);
        int queued();
    }
}
