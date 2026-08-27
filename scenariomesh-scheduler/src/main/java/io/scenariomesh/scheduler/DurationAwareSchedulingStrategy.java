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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/** Longest-estimated-task-first scheduling while retaining lifecycle-scope affinity. */
public final class DurationAwareSchedulingStrategy implements SchedulingStrategy {
    private static final String EXECUTION_SCOPE_ID = "executionScopeId";
    private static final String ESTIMATE = "estimatedDurationMillis";

    private final Object lock = new Object();
    private final List<Entry> queue = new ArrayList<>();
    private final Map<String, Long> laneByScope = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public void load(Collection<ScenarioTask> tasks) {
        synchronized (lock) {
            queue.clear();
            laneByScope.clear();
            sequence.set(0);
            for (ScenarioTask task : tasks) queue.add(entry(task));
            sort();
        }
    }

    @Override
    public ScenarioTask nextEligible(Predicate<ScenarioTask> eligible) {
        Objects.requireNonNull(eligible, "eligible");
        long lane = Thread.currentThread().getId();
        synchronized (lock) {
            for (int index = 0; index < queue.size(); index++) {
                ScenarioTask task = queue.get(index).task();
                if (eligible.test(task) && affinityAllows(task, lane)) {
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
            queue.add(entry(Objects.requireNonNull(task, "task")));
            sort();
        }
    }

    @Override
    public int queued() {
        synchronized (lock) { return queue.size(); }
    }

    private Entry entry(ScenarioTask task) {
        long estimate = 0L;
        try { estimate = Math.max(0L, Long.parseLong(task.metadata().getOrDefault(ESTIMATE, "0"))); }
        catch (NumberFormatException ignored) { estimate = 0L; }
        return new Entry(task, estimate, sequence.getAndIncrement());
    }

    private void sort() {
        queue.sort(Comparator.comparingLong(Entry::estimateMillis).reversed().thenComparingLong(Entry::sequence));
    }

    private boolean affinityAllows(ScenarioTask task, long lane) {
        String scopeId = task.metadata().get(EXECUTION_SCOPE_ID);
        if (scopeId == null || scopeId.isBlank()) return true;
        Long owner = laneByScope.putIfAbsent(scopeId, lane);
        return owner == null || owner == lane;
    }

    private record Entry(ScenarioTask task, long estimateMillis, long sequence) {}
}
