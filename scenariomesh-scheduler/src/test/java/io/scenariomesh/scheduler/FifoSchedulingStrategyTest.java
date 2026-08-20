package io.scenariomesh.scheduler;

import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FifoSchedulingStrategyTest {
    @Test
    void findsEligibleWorkBehindAnIneligibleHeadWithoutDroppingEitherTask() {
        FifoSchedulingStrategy scheduler = new FifoSchedulingStrategy();
        ScenarioTask blocked = task("blocked");
        ScenarioTask eligible = task("eligible");
        scheduler.load(List.of(blocked, eligible));

        ScenarioTask selected = scheduler.nextEligible(task -> task.id().value().equals("eligible"));

        assertEquals(eligible, selected);
        assertEquals(1, scheduler.queued());
        assertEquals(blocked, scheduler.nextEligible(task -> true));
        assertEquals(0, scheduler.queued());
    }

    @Test
    void returnsNullAfterOneRotationWhenNothingIsEligible() {
        FifoSchedulingStrategy scheduler = new FifoSchedulingStrategy();
        ScenarioTask first = task("first");
        ScenarioTask second = task("second");
        scheduler.load(List.of(first, second));

        assertNull(scheduler.nextEligible(task -> false));
        assertEquals(2, scheduler.queued());
        assertEquals(first, scheduler.nextEligible(task -> true));
    }

    private ScenarioTask task(String id) {
        return new ScenarioTask(
                new ScenarioId(id), id, "adapter", "framework",
                null, null, id, Set.of(), Map.of());
    }
}
