package io.scenariomesh.coordinator;

import io.scenariomesh.coordinator.distributed.RemoteWorkerRegistration;
import io.scenariomesh.coordinator.distributed.WorkerRegistrationValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedRemoteWorkersTest {
    @Test
    void acceptsHeterogeneousWorkersWhenThePreparedSetCoversTheSelectedRuntime() {
        List<RemoteWorkerRegistration> registrations = List.of(
                worker("a", "fp", Set.of("testng"), Set.of()),
                worker("b", "fp", Set.of("junit-platform"), Set.of("junit-jupiter", "cucumber")));

        assertDoesNotThrow(() -> PreparedRemoteWorkers.verifyCapabilityCoverage(
                registrations, Set.of("testng", "junit-platform"), Set.of("junit-jupiter", "cucumber")));
    }

    @Test
    void rejectsTakeoverWhenNoWorkerCoversASelectedAdapter() {
        List<RemoteWorkerRegistration> registrations = List.of(
                worker("a", "fp", Set.of("junit-platform"), Set.of("junit-jupiter")),
                worker("b", "fp", Set.of("junit-platform"), Set.of("junit-jupiter")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PreparedRemoteWorkers.verifyCapabilityCoverage(
                        registrations, Set.of("testng"), Set.of()));
        assertTrue(failure.getMessage().contains("testng"));
    }

    @Test
    void rejectsTakeoverWhenEngineAndJUnitAdapterAreNotOnTheSameWorker() {
        List<RemoteWorkerRegistration> registrations = List.of(
                worker("a", "fp", Set.of("junit-platform"), Set.of("junit-jupiter")),
                worker("b", "fp", Set.of("testng"), Set.of("cucumber")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PreparedRemoteWorkers.verifyCapabilityCoverage(
                        registrations, Set.of("junit-platform"), Set.of("cucumber")));
        assertTrue(failure.getMessage().contains("cucumber"));
        assertTrue(failure.getMessage().contains("junit-platform"));
    }

    @Test
    void acceptsWhenOnlyOneWorkerProvidesARequiredEngine() {
        List<RemoteWorkerRegistration> registrations = List.of(
                worker("a", "fp", Set.of("junit-platform"), Set.of("junit-jupiter")),
                worker("b", "fp", Set.of("junit-platform"), Set.of("cucumber")));

        assertDoesNotThrow(() -> PreparedRemoteWorkers.verifyCapabilityCoverage(
                registrations, Set.of("junit-platform"), Set.of("cucumber")));
    }

    @Test
    void assignsWorkersOnlyToTheMatchingExecutionFingerprint() {
        List<PreparedRemoteWorkers.ExecutionRequirement> requirements = List.of(
                requirement("fast", "fp-fast", Set.of("junit-platform"), Set.of("junit-jupiter")),
                requirement("slow", "fp-slow", Set.of("testng"), Set.of()));
        List<List<RemoteWorkerRegistration>> cohorts = List.of(List.of(), List.of());
        WorkerRegistrationValidator validator = new WorkerRegistrationValidator();

        assertEquals(0, PreparedRemoteWorkers.selectCohort(requirements, cohorts,
                worker("fast-worker", "fp-fast", Set.of("junit-platform"), Set.of("junit-jupiter")),
                2, 1, validator));
        assertEquals(1, PreparedRemoteWorkers.selectCohort(requirements, cohorts,
                worker("slow-worker", "fp-slow", Set.of("testng"), Set.of()),
                2, 1, validator));
        assertEquals(-1, PreparedRemoteWorkers.selectCohort(requirements, cohorts,
                worker("wrong-runtime", "fp-other", Set.of("junit-platform", "testng"), Set.of("junit-jupiter")),
                2, 1, validator));
    }

    @Test
    void sameRuntimeFingerprintPrefersTheExecutionWithMissingCapabilityCoverage() {
        List<PreparedRemoteWorkers.ExecutionRequirement> requirements = List.of(
                requirement("junit", "fp", Set.of("junit-platform"), Set.of("junit-jupiter")),
                requirement("testng", "fp", Set.of("testng"), Set.of()));
        List<List<RemoteWorkerRegistration>> cohorts = List.of(
                List.of(worker("junit-existing", "fp", Set.of("junit-platform"), Set.of("junit-jupiter"))),
                List.of());
        WorkerRegistrationValidator validator = new WorkerRegistrationValidator();

        assertEquals(1, PreparedRemoteWorkers.selectCohort(requirements, cohorts,
                worker("testng-new", "fp", Set.of("testng"), Set.of()),
                2, 1, validator));
    }

    @Test
    void separateCohortsDoNotReuseAWorkerAfterOneExecutionIsFull() {
        List<PreparedRemoteWorkers.ExecutionRequirement> requirements = List.of(
                requirement("one", "fp", Set.of("junit-platform"), Set.of("junit-jupiter")),
                requirement("two", "fp", Set.of("junit-platform"), Set.of("junit-jupiter")));
        List<List<RemoteWorkerRegistration>> cohorts = List.of(
                List.of(worker("one-a", "fp", Set.of("junit-platform"), Set.of("junit-jupiter"))),
                List.of());
        WorkerRegistrationValidator validator = new WorkerRegistrationValidator();

        assertEquals(1, PreparedRemoteWorkers.selectCohort(requirements, cohorts,
                worker("two-a", "fp", Set.of("junit-platform"), Set.of("junit-jupiter")),
                1, 1, validator));
    }

    private PreparedRemoteWorkers.ExecutionRequirement requirement(String id, String fingerprint,
                                                                    Set<String> adapters, Set<String> engines) {
        return new PreparedRemoteWorkers.ExecutionRequirement(id, adapters, engines, fingerprint);
    }

    private RemoteWorkerRegistration worker(String id, String fingerprint,
                                            Set<String> adapters, Set<String> engines) {
        return new RemoteWorkerRegistration(id, fingerprint, 1, 21, "Linux", "amd64", adapters, engines, Map.of());
    }
}
