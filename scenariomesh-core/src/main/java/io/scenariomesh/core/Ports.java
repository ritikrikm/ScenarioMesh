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

    public interface ScenarioAdapter {
        String id();
        String framework();
        boolean isAvailable(ClassLoader classLoader);
        List<ScenarioTask> discover(AdapterContext context) throws Exception;
        ExecutionResult execute(ScenarioTask task, ExecutionContext context) throws Exception;

        /**
         * Executes one scheduler work unit atomically from the coordinator's point of view.
         *
         * <p>The default implementation preserves existing leaf-oriented adapters by
         * executing every supplied task independently. Lifecycle-scoped adapters override
         * this method so one class/suite/engine lifecycle is executed once and all of its
         * leaf outcomes are returned in the same worker response. This is required so a
         * worker can be recycled after the response without forcing the coordinator to
         * rerun an already-completed lifecycle scope.</p>
         */
        default List<ExecutionResult> executeBatch(List<ScenarioTask> tasks, ExecutionContext context) throws Exception {
            if (tasks == null || tasks.isEmpty()) {
                throw new IllegalArgumentException("ScenarioAdapter.executeBatch requires at least one task");
            }
            List<ExecutionResult> results = new ArrayList<>(tasks.size());
            for (ScenarioTask task : tasks) {
                results.add(execute(task, context));
            }
            return List.copyOf(results);
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

    /**
     * Optional worker-side cleanup extension loaded with {@link java.util.ServiceLoader}.
     * Implementations must clean only resources owned by the completed task/worker.
     */
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
