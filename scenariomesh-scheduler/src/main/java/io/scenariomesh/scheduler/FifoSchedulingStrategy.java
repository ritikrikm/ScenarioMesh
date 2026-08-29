package io.scenariomesh.scheduler;

import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.SchedulingStrategy;
import io.scenariomesh.core.TaskMetadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Backward-compatible scheduler name with selectable product scheduling semantics.
 *
 * <p>By default, when tasks carry an {@code estimatedDurationMillis} learned from prior runs,
 * longer tasks are started first (LPT) to reduce tail latency. With history awareness disabled,
 * ordering is strict FIFO. Lifecycle-scope affinity is always enforced against an explicit,
 * stable execution-lane identity supplied by the coordinator.</p>
 */
public final class FifoSchedulingStrategy implements SchedulingStrategy {
    private final Object lock = new Object();
    private final List<Entry> queue = new ArrayList<>();
    private final Map<String, String> laneByScope = new LinkedHashMap<>();
    private final boolean historyAware;
    private long sequence;

    public FifoSchedulingStrategy() {
        this(true);
    }

    public FifoSchedulingStrategy(boolean historyAware) {
        this.historyAware = historyAware;
    }

    @Override
    public void load(Collection<ScenarioTask> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        synchronized (lock) {
            queue.clear();
            laneByScope.clear();
            sequence = 0L;
            for (ScenarioTask task : tasks) queue.add(new Entry(Objects.requireNonNull(task, "task"), estimate(task), sequence++));
            order();
        }
    }

    @Override
    public ScenarioTask nextEligible(String executionLaneId, Predicate<ScenarioTask> eligible) {
        String laneId = requireLaneId(executionLaneId);
        Objects.requireNonNull(eligible, "eligible");
        synchronized (lock) {
            for (int index = 0; index < queue.size(); index++) {
                ScenarioTask task = queue.get(index).task();
                if (eligible.test(task) && affinityAllows(task, laneId)) {
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
            ScenarioTask value = Objects.requireNonNull(task, "task");
            queue.add(new Entry(value, estimate(value), sequence++));
            order();
        }
    }

    @Override
    public int queued() {
        synchronized (lock) { return queue.size(); }
    }

    private void order() {
        Comparator<Entry> comparator = historyAware
                ? Comparator.comparingLong(Entry::estimatedMillis).reversed().thenComparingLong(Entry::sequence)
                : Comparator.comparingLong(Entry::sequence);
        queue.sort(comparator);
    }

    private long estimate(ScenarioTask task) {
        if (!historyAware) return 0L;
        String value = task.metadata().get(TaskMetadata.ESTIMATED_DURATION_MILLIS);
        if (value == null || value.isBlank()) return 0L;
        try { return Math.max(0L, Long.parseLong(value)); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private boolean affinityAllows(ScenarioTask task, String laneId) {
        String scopeId = task.metadata().get(TaskMetadata.EXECUTION_SCOPE_ID);
        if (scopeId == null || scopeId.isBlank()) return true;
        String owner = laneByScope.get(scopeId);
        if (owner == null) {
            laneByScope.put(scopeId, laneId);
            return true;
        }
        return owner.equals(laneId);
    }

    private String requireLaneId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("executionLaneId must not be blank");
        }
        return value;
    }

    private record Entry(ScenarioTask task, long estimatedMillis, long sequence) {}
}
