package io.scenariomesh.coordinator;

import io.scenariomesh.coordinator.distributed.RemoteWorkerRegistration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedRemoteWorkersTest {
    @Test
    void acceptsHeterogeneousWorkersWhenThePreparedSetCoversTheSelectedRuntime() {
        List<RemoteWorkerRegistration> registrations = List.of(
                worker("a", Set.of("testng"), Set.of()),
                worker("b", Set.of("junit-platform"), Set.of("junit-jupiter", "cucumber")));

        assertDoesNotThrow(() -> PreparedRemoteWorkers.verifyCapabilityCoverage(
                registrations, Set.of("testng", "junit-platform"), Set.of("junit-jupiter", "cucumber")));
    }

    @Test
    void rejectsTakeoverWhenNoWorkerCoversASelectedAdapter() {
        List<RemoteWorkerRegistration> registrations = List.of(
                worker("a", Set.of("junit-platform"), Set.of("junit-jupiter")),
                worker("b", Set.of("junit-platform"), Set.of("junit-jupiter")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PreparedRemoteWorkers.verifyCapabilityCoverage(
                        registrations, Set.of("testng"), Set.of()));
        assertTrue(failure.getMessage().contains("testng"));
    }

    @Test
    void rejectsTakeoverWhenEngineAndJUnitAdapterAreNotOnTheSameWorker() {
        List<RemoteWorkerRegistration> registrations = List.of(
                worker("a", Set.of("junit-platform"), Set.of("junit-jupiter")),
                worker("b", Set.of("testng"), Set.of("cucumber")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PreparedRemoteWorkers.verifyCapabilityCoverage(
                        registrations, Set.of("junit-platform"), Set.of("cucumber")));
        assertTrue(failure.getMessage().contains("cucumber"));
        assertTrue(failure.getMessage().contains("junit-platform"));
    }

    @Test
    void acceptsWhenOnlyOneWorkerProvidesARequiredEngine() {
        List<RemoteWorkerRegistration> registrations = List.of(
                worker("a", Set.of("junit-platform"), Set.of("junit-jupiter")),
                worker("b", Set.of("junit-platform"), Set.of("cucumber")));

        assertDoesNotThrow(() -> PreparedRemoteWorkers.verifyCapabilityCoverage(
                registrations, Set.of("junit-platform"), Set.of("cucumber")));
    }

    private RemoteWorkerRegistration worker(String id, Set<String> adapters, Set<String> engines) {
        return new RemoteWorkerRegistration(id, "fp", 1, 21, "Linux", "amd64", adapters, engines, Map.of());
    }
}
