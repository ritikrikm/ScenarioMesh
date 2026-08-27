package io.scenariomesh.coordinator.distributed;

import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;
import io.scenariomesh.protocol.Protocol.WorkerCapabilities;

import java.util.Map;
import java.util.Objects;

/** Validates protocol-level worker registration without coupling it to a transport. */
public final class WorkerRegistrationValidator {
    public RemoteWorkerRegistration requireRegistration(Envelope hello, String expectedToken) {
        Objects.requireNonNull(hello, "hello");
        if (hello.protocolVersion() != Protocol.VERSION) {
            throw new IllegalArgumentException("unsupported worker protocol version " + hello.protocolVersion());
        }
        if (hello.type() != Protocol.Type.HELLO) {
            throw new IllegalArgumentException("worker registration must use HELLO");
        }
        if (!Objects.equals(expectedToken, hello.token())) {
            throw new IllegalArgumentException("worker registration token mismatch");
        }
        if (hello.workerId() == null || hello.workerId().isBlank()) {
            throw new IllegalArgumentException("worker registration requires workerId");
        }
        WorkerCapabilities capabilities = Objects.requireNonNull(
                hello.capabilities(), "worker registration requires capabilities");
        if (capabilities.javaFeature() < 17) {
            throw new IllegalArgumentException("worker Java must be 17 or newer");
        }
        if (capabilities.adapterIds().isEmpty()) {
            throw new IllegalArgumentException("worker registration must advertise at least one executable adapter");
        }
        return new RemoteWorkerRegistration(
                hello.workerId(), capabilities.runtimeFingerprint(), capabilities.slots(),
                capabilities.javaFeature(), capabilities.osName(), capabilities.architecture(),
                capabilities.adapterIds(), capabilities.engineIds(), Map.of("agentId", capabilities.agentId()));
    }

    public void requireCanRun(RemoteWorkerRegistration registration, String adapterId, String engineId) {
        Objects.requireNonNull(registration, "registration");
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("work requires adapterId");
        }
        if (!registration.adapterIds().contains(adapterId)) {
            throw new CapabilityMismatchException("worker " + registration.workerId()
                    + " does not advertise adapter " + adapterId);
        }
        if (engineId != null && !engineId.isBlank() && !registration.engineIds().contains(engineId)) {
            throw new CapabilityMismatchException("worker " + registration.workerId()
                    + " does not advertise engine " + engineId);
        }
    }

    public boolean canRun(RemoteWorkerRegistration registration, String adapterId, String engineId) {
        try {
            requireCanRun(registration, adapterId, engineId);
            return true;
        } catch (CapabilityMismatchException | IllegalArgumentException exception) {
            return false;
        }
    }

    public static final class CapabilityMismatchException extends IllegalStateException {
        public CapabilityMismatchException(String message) { super(message); }
    }
}
