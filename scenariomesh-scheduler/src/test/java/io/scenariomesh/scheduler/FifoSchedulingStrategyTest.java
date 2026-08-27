package io.scenariomesh.scheduler;

import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void lifecycleScopeIsClaimedByExactlyOneWorkerLane() throws Exception {
        FifoSchedulingStrategy scheduler = new FifoSchedulingStrategy();
        scheduler.load(List.of(scopedTask("a", "class:LoginTest"), scopedTask("b", "class:LoginTest")));

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<ScenarioTask> first = new AtomicReference<>();
        AtomicReference<ScenarioTask> second = new AtomicReference<>();

        Thread laneOne = new Thread(() -> {
            await(start);
            first.set(scheduler.nextEligible(task -> true));
        });
        Thread laneTwo = new Thread(() -> {
            await(start);
            second.set(scheduler.nextEligible(task -> true));
        });
        laneOne.start();
        laneTwo.start();
        start.countDown();
        laneOne.join();
        laneTwo.join();

        int selected = (first.get() == null ? 0 : 1) + (second.get() == null ? 0 : 1);
        assertEquals(1, selected, "only the lane that atomically claims the scope may receive its work");
        assertEquals(1, scheduler.queued());
    }

    @Test
    void sameWorkerLaneReceivesRemainingLeavesFromItsClaimedScope() {
        FifoSchedulingStrategy scheduler = new FifoSchedulingStrategy();
        ScenarioTask first = scopedTask("a", "class:LoginTest");
        ScenarioTask second = scopedTask("b", "class:LoginTest");
        scheduler.load(List.of(first, second));

        assertEquals(first, scheduler.nextEligible(task -> true));
        assertEquals(second, scheduler.nextEligible(task -> true));
    }

    @Test
    void unrelatedScopesRemainParallelizable() throws Exception {
        FifoSchedulingStrategy scheduler = new FifoSchedulingStrategy();
        scheduler.load(List.of(scopedTask("login", "class:LoginTest"), scopedTask("payment", "class:PaymentTest")));

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<ScenarioTask> first = new AtomicReference<>();
        AtomicReference<ScenarioTask> second = new AtomicReference<>();
        Thread laneOne = new Thread(() -> { await(start); first.set(scheduler.nextEligible(task -> true)); });
        Thread laneTwo = new Thread(() -> { await(start); second.set(scheduler.nextEligible(task -> true)); });
        laneOne.start();
        laneTwo.start();
        start.countDown();
        laneOne.join();
        laneTwo.join();

        assertTrue(first.get() != null && second.get() != null,
                "independent lifecycle scopes should remain available to independent worker lanes");
        assertEquals(0, scheduler.queued());
    }

    private ScenarioTask task(String id) {
        return new ScenarioTask(
                new ScenarioId(id), id, "adapter", "framework",
                null, null, id, Set.of(), Map.of());
    }

    private ScenarioTask scopedTask(String id, String scopeId) {
        return new ScenarioTask(
                new ScenarioId(id), id, "junit-platform", "junit5",
                null, null, id, Set.of(), Map.of("executionScopeId", scopeId));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        }
    }
}
