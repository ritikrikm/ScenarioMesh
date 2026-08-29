package io.scenariomesh.scheduler;

import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerScaleTest {
    @Test
    void drainsFiveThousandLogicalTasksExactlyOnceWithDeterministicLptOrder() {
        FifoSchedulingStrategy scheduler = new FifoSchedulingStrategy();
        List<ScenarioTask> tasks = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            long estimate = (i % 100) + 1L;
            tasks.add(task(String.format("task-%04d", i), estimate));
        }
        scheduler.load(tasks);
        Set<String> seen = new HashSet<>();
        long previousEstimate = Long.MAX_VALUE;
        while (scheduler.queued() > 0) {
            ScenarioTask task = scheduler.nextEligible("test-lane", ignored -> true);
            assertTrue(task != null);
            assertTrue(seen.add(task.id().value()), "duplicate scheduled task " + task.id().value());
            long estimate = Long.parseLong(task.metadata().get("estimatedDurationMillis"));
            assertTrue(estimate <= previousEstimate, "LPT order regressed");
            previousEstimate = estimate;
        }
        assertEquals(5_000, seen.size());
        assertEquals(0, scheduler.queued());
    }

    @Test
    void coldStartFiveThousandTasksPreservesInputOrder() {
        FifoSchedulingStrategy scheduler = new FifoSchedulingStrategy();
        List<ScenarioTask> tasks = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) tasks.add(task("cold-" + i, null));
        scheduler.load(tasks);
        for (int i = 0; i < 5_000; i++) {
            ScenarioTask task = scheduler.nextEligible("test-lane", ignored -> true);
            assertEquals("cold-" + i, task.id().value());
        }
    }

    private ScenarioTask task(String id, Long estimate) {
        Map<String, String> metadata = estimate == null ? Map.of()
                : Map.of("estimatedDurationMillis", Long.toString(estimate));
        return new ScenarioTask(new ScenarioId(id), id, "junit-platform", "junit5",
                null, null, id, Set.of(), metadata);
    }
}
