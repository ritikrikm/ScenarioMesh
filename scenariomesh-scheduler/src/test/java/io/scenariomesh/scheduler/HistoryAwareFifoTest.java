package io.scenariomesh.scheduler;

import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryAwareFifoTest {
    @Test
    void startsLongestKnownTaskFirst() {
        FifoSchedulingStrategy scheduler = new FifoSchedulingStrategy();
        scheduler.load(List.of(task("short", 10), task("long", 1000), task("medium", 100)));
        assertEquals("long", scheduler.nextEligible(task -> true).id().value());
        assertEquals("medium", scheduler.nextEligible(task -> true).id().value());
        assertEquals("short", scheduler.nextEligible(task -> true).id().value());
    }

    @Test
    void preservesFifoWhenNoHistoryExists() {
        FifoSchedulingStrategy scheduler = new FifoSchedulingStrategy();
        scheduler.load(List.of(task("a", null), task("b", null), task("c", null)));
        assertEquals("a", scheduler.nextEligible(task -> true).id().value());
        assertEquals("b", scheduler.nextEligible(task -> true).id().value());
        assertEquals("c", scheduler.nextEligible(task -> true).id().value());
    }

    private ScenarioTask task(String id, Integer estimate) {
        Map<String, String> metadata = estimate == null ? Map.of() : Map.of("estimatedDurationMillis", estimate.toString());
        return new ScenarioTask(new ScenarioId(id), id, "adapter", "framework", null, null, id, Set.of(), metadata);
    }
}
