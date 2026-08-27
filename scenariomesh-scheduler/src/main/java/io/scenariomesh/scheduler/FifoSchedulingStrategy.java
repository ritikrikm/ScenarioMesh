package io.scenariomesh.scheduler;

import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.SchedulingStrategy;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;

/**
 * FIFO scheduling with lifecycle-scope affinity.
 *
 * <p>WorkerPool dedicates one coordinator loop thread to each worker lane. Tasks
 * carrying the same {@code executionScopeId} are atomically bound to the first
 * lane that claims them, so class/suite/run-scoped adapter state is never split
 * across concurrently active worker JVMs. Unscoped tasks retain normal FIFO
 * behavior.</p>
 */
public final class FifoSchedulingStrategy implements SchedulingStrategy {
    private static final String EXECUTION_SCOPE_ID = "executionScopeId";

    private final BlockingQueue<ScenarioTask> queue = new LinkedBlockingQueue<>();
    private final Map<String, Long> laneByScope = new ConcurrentHashMap<>();

    @Override
    public void load(Collection<ScenarioTask> tasks) {
        queue.clear();
        laneByScope.clear();
        queue.addAll(tasks);
    }

    @Override
    public ScenarioTask nextEligible(Predicate<ScenarioTask> eligible) {
        Objects.requireNonNull(eligible, "eligible");
        long currentLane = Thread.currentThread().getId();
        int candidatesToInspect = queue.size();
        for (int index = 0; index < candidatesToInspect; index++) {
            ScenarioTask task = queue.poll();
            if (task == null) {
                return null;
            }
            if (eligible.test(task) && affinityAllows(task, currentLane)) {
                return task;
            }
            queue.offer(task);
        }
        return null;
    }

    @Override
    public void requeue(ScenarioTask task) {
        queue.offer(Objects.requireNonNull(task, "task"));
    }

    @Override
    public int queued() {
        return queue.size();
    }

    private boolean affinityAllows(ScenarioTask task, long currentLane) {
        String scopeId = task.metadata().get(EXECUTION_SCOPE_ID);
        if (scopeId == null || scopeId.isBlank()) {
            return true;
        }
        Long owner = laneByScope.putIfAbsent(scopeId, currentLane);
        return owner == null || owner == currentLane;
    }
}
