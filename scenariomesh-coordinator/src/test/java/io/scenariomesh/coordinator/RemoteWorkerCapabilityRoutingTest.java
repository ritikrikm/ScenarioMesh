package io.scenariomesh.coordinator;

import io.scenariomesh.coordinator.distributed.RemoteWorkerRegistration;
import io.scenariomesh.coordinator.distributed.WorkerRegistrationValidator;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteWorkerCapabilityRoutingTest {
    private final WorkerRegistrationValidator validator = new WorkerRegistrationValidator();

    @Test
    void routesJUnitTaskOnlyToWorkerWithMatchingAdapterAndEngine() {
        ScenarioTask task = task("junit-platform", "junit-jupiter");
        RemoteWorkerRegistration jupiter = worker("jupiter", Set.of("junit-platform"), Set.of("junit-jupiter"));
        RemoteWorkerRegistration cucumber = worker("cucumber", Set.of("junit-platform"), Set.of("cucumber"));
        RemoteWorkerRegistration testng = worker("testng", Set.of("testng"), Set.of());

        assertTrue(RemoteWorkerPool.canRun(validator, jupiter, task));
        assertFalse(RemoteWorkerPool.canRun(validator, cucumber, task));
        assertFalse(RemoteWorkerPool.canRun(validator, testng, task));
    }

    @Test
    void routesNonJUnitTaskByAdapterWithoutInventingAnEngineRequirement() {
        ScenarioTask task = task("testng", null);
        RemoteWorkerRegistration testng = worker("testng", Set.of("testng"), Set.of());
        RemoteWorkerRegistration jupiter = worker("jupiter", Set.of("junit-platform"), Set.of("junit-jupiter"));

        assertNull(RemoteWorkerPool.requiredEngineId(task));
        assertTrue(RemoteWorkerPool.canRun(validator, testng, task));
        assertFalse(RemoteWorkerPool.canRun(validator, jupiter, task));
    }

    private ScenarioTask task(String adapter, String engine) {
        Map<String, String> metadata = engine == null ? Map.of() : Map.of("requiredEngineId", engine);
        return new ScenarioTask(new ScenarioId(adapter + ":task"), "task", adapter, adapter,
                null, null, "selector", Set.of(), metadata);
    }

    private RemoteWorkerRegistration worker(String id, Set<String> adapters, Set<String> engines) {
        return new RemoteWorkerRegistration(id, "fp", 1, 21, "Linux", "amd64", adapters, engines, Map.of());
    }
}
