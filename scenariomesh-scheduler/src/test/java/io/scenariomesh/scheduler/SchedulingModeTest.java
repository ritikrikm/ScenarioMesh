package io.scenariomesh.scheduler;

import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchedulingModeTest {
    @Test
    void historyAwareModeStartsLongestEstimateFirst() {
        FifoSchedulingStrategy scheduler = new FifoSchedulingStrategy(true);
        ScenarioTask shortTask = task("short", 10);
        ScenarioTask longTask = task("long", 1000);
        scheduler.load(List.of(shortTask, longTask));

        assertEquals(longTask, scheduler.nextEligible("test-lane", task -> true));
        assertEquals(shortTask, scheduler.nextEligible("test-lane", task -> true));
    }

    @Test
    void strictFifoIgnoresDurationEstimates() {
        FifoSchedulingStrategy scheduler = new FifoSchedulingStrategy(false);
        ScenarioTask shortTask = task("short", 10);
        ScenarioTask longTask = task("long", 1000);
        scheduler.load(List.of(shortTask, longTask));

        assertEquals(shortTask, scheduler.nextEligible("test-lane", task -> true));
        assertEquals(longTask, scheduler.nextEligible("test-lane", task -> true));
    }

    private ScenarioTask task(String id, long estimateMillis) {
        return new ScenarioTask(new ScenarioId(id), id, "adapter", "framework", null, null, id,
                Set.of(), Map.of("estimatedDurationMillis", Long.toString(estimateMillis)));
    }
}
