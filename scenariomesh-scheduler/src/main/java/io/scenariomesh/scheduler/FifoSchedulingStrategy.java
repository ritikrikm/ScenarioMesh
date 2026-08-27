package io.scenariomesh.scheduler;

import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.SchedulingStrategy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Backward-compatible scheduler name with product scheduling semantics.
 *
 * <p>With no execution history, ordering is exactly FIFO. When tasks carry an
 * {@code estimatedDurationMillis} learned from prior runs, longer tasks are started first
 * (LPT) to reduce tail latency. Lifecycle-scope affinity is always enforced.</p>
 */
public final class FifoSchedulingStrategy implements SchedulingStrategy {
    private static final String EXECUTION_SCOPE_ID = "executionScopeId";
    private static final String ESTIMATED_DURATION_MILLIS = "estimatedDurationMillis";

    private final Object lock = new Object();
    private final List<Entry> queue = new ArrayList<>();
    private final Map<String, Long> laneByScope = new ConcurrentHashMap<>();
    private long sequence;

    @Override
    public void load(Collection<ScenarioTask> tasks) {
        synchronized (lock) {
            queue.clear();
            laneByScope.clear();
            sequence = 0L;
            for (ScenarioTask task : tasks) queue.add(new Entry(task, estimate(task), sequence++));
            order();
        }
    }

    @Override
    public ScenarioTask nextEligible(Predicate<ScenarioTask> eligible) {
        Objects.requireNonNull(eligible, "eligible");
        long currentLane = Thread.currentThread().getId();
        synchronized (lock) {
            for (int index = 0; index < queue.size(); index++) {
                ScenarioTask task = queue.get(index).task();
                if (eligible.test(task) && affinityAllows(task, currentLane)) {
                    queue.remove(index);
                    return task;
                }
            }
            return null;
        }
    }

    @Override
    public void requeue(ScenarioTask task) {
        synchronized (lock) {
            queue.add(new Entry(Objects.requireNonNull(task, "task"), estimate(task), sequence++));
            order();
        }
    }

    @Override
    public int queued() {
        synchronized (lock) { return queue.size(); }
    }

    private void order() {
        queue.sort(Comparator.comparingLong(Entry::estimatedMillis).reversed()
                .thenComparingLong(Entry::sequence));
    }

    private long estimate(ScenarioTask task) {
        String value = task.metadata().get(ESTIMATED_DURATION_MILLIS);
        if (value == null || value.isBlank()) return 0L;
        try { return Math.max(0L, Long.parseLong(value)); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private boolean affinityAllows(ScenarioTask task, long currentLane) {
        String scopeId = task.metadata().get(EXECUTION_SCOPE_ID);
        if (scopeId == null || scopeId.isBlank()) return true;
        Long owner = laneByScope.putIfAbsent(scopeId, currentLane);
        return owner == null || owner == currentLane;
    }

    private record Entry(ScenarioTask task, long estimatedMillis, long sequence) {}
}
