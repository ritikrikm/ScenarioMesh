package io.scenariomesh.coordinator.distributed;

import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;
import io.scenariomesh.protocol.Protocol.WorkerCapabilities;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkerRegistrationValidatorTest {
    private final WorkerRegistrationValidator validator = new WorkerRegistrationValidator();

    @Test
    void convertsValidHelloIntoSchedulableRegistration() {
        WorkerCapabilities capabilities = new WorkerCapabilities(
                "jenkins-linux-a", 3, 21, "Linux", "amd64", "fp",
                Set.of("junit-platform", "testng"), Set.of("junit-jupiter"));
        RemoteWorkerRegistration registration = validator.requireRegistration(
                Envelope.hello("worker-a", "secret", capabilities), "secret");

        assertEquals("worker-a", registration.workerId());
        assertEquals(3, registration.slots());
        assertEquals(Set.of("junit-platform", "testng"), registration.adapterIds());
        assertEquals("jenkins-linux-a", registration.labels().get("agentId"));
        validator.requireCanRun(registration, "junit-platform", "junit-jupiter");
    }

    @Test
    void rejectsMissingCapabilitiesAndUnsupportedJava() {
        assertThrows(NullPointerException.class,
                () -> validator.requireRegistration(Envelope.hello("worker-a", "secret"), "secret"));

        WorkerCapabilities oldJava = new WorkerCapabilities(
                "legacy", 1, 11, "Linux", "amd64", "fp",
                Set.of("junit-platform"), Set.of());
        assertThrows(IllegalArgumentException.class,
                () -> validator.requireRegistration(Envelope.hello("worker-a", "secret", oldJava), "secret"));
    }

    @Test
    void rejectsWorkerThatCannotRunRequestedAdapterOrAdvertisedEngine() {
        RemoteWorkerRegistration registration = validator.requireRegistration(
                Envelope.hello("worker-a", "secret", new WorkerCapabilities(
                        "agent", 1, 21, "Linux", "amd64", "fp",
                        Set.of("junit-platform"), Set.of("junit-jupiter"))), "secret");

        assertThrows(IllegalStateException.class,
                () -> validator.requireCanRun(registration, "testng", null));
        assertThrows(IllegalStateException.class,
                () -> validator.requireCanRun(registration, "junit-platform", "cucumber"));
    }

    @Test
    void emptyEngineInventoryDefersEngineProofToTargetPreflight() {
        RemoteWorkerRegistration registration = validator.requireRegistration(
                Envelope.hello("worker-a", "secret", new WorkerCapabilities(
                        "agent", 1, 21, "Linux", "amd64", "fp",
                        Set.of("junit-platform"), Set.of())), "secret");

        validator.requireCanRun(registration, "junit-platform", "cucumber");
    }
}
