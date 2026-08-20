package io.scenariomesh.scheduler;

import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.SchedulingStrategy;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;

public final class FifoSchedulingStrategy implements SchedulingStrategy {
    private final BlockingQueue<ScenarioTask> queue = new LinkedBlockingQueue<>();

    @Override
    public void load(Collection<ScenarioTask> tasks) {
        queue.clear();
        queue.addAll(tasks);
    }

    @Override
    public ScenarioTask nextEligible(Predicate<ScenarioTask> eligible) {
        Objects.requireNonNull(eligible, "eligible");
        int candidatesToInspect = queue.size();
        for (int index = 0; index < candidatesToInspect; index++) {
            ScenarioTask task = queue.poll();
            if (task == null) {
                return null;
            }
            if (eligible.test(task)) {
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
}
