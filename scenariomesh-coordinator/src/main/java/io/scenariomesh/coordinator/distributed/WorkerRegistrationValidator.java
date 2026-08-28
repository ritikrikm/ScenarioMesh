package io.scenariomesh.coordinator.distributed;

import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;
import io.scenariomesh.protocol.Protocol.WorkerCapabilities;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Validates protocol-level worker registration without coupling it to a transport. */
public final class WorkerRegistrationValidator {
    static final String PROTOCOL_VERSION_LABEL = "protocolVersion";
    static final String PROTOCOL_NEGOTIATED_LABEL = "protocolNegotiated";

    public RemoteWorkerRegistration requireRegistration(Envelope hello, String expectedToken) {
        Objects.requireNonNull(hello, "hello");
        if (hello.protocolVersion() != Protocol.BOOTSTRAP_VERSION) {
            throw new IllegalArgumentException("unsupported worker bootstrap protocol version " + hello.protocolVersion());
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

        int negotiatedProtocol = negotiateProtocol(capabilities);
        Map<String, String> labels = new HashMap<>();
        labels.put("agentId", capabilities.agentId());
        labels.put(PROTOCOL_VERSION_LABEL, Integer.toString(negotiatedProtocol));
        labels.put(PROTOCOL_NEGOTIATED_LABEL, Boolean.toString(capabilities.advertisesProtocolRange()));

        return new RemoteWorkerRegistration(
                hello.workerId(), capabilities.runtimeFingerprint(), capabilities.slots(),
                capabilities.javaFeature(), capabilities.osName(), capabilities.architecture(),
                capabilities.adapterIds(), capabilities.engineIds(), Map.copyOf(labels));
    }

    static int negotiatedProtocolVersion(RemoteWorkerRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        String value = registration.labels().get(PROTOCOL_VERSION_LABEL);
        if (value == null) return Protocol.BOOTSTRAP_VERSION;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("remote worker registration contains invalid negotiated protocol version", invalid);
        }
    }

    static boolean negotiationAware(RemoteWorkerRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        return Boolean.parseBoolean(registration.labels().getOrDefault(PROTOCOL_NEGOTIATED_LABEL, "false"));
    }

    private static int negotiateProtocol(WorkerCapabilities capabilities) {
        if (!capabilities.advertisesProtocolRange()) {
            // A legacy v8 worker did not advertise a range. Never assume it understands v9.
            return Protocol.BOOTSTRAP_VERSION;
        }
        int lower = Math.max(Protocol.MIN_SUPPORTED_VERSION, capabilities.minProtocolVersion());
        int upper = Math.min(Protocol.VERSION, capabilities.maxProtocolVersion());
        if (lower > upper) {
            throw new IllegalArgumentException("worker protocol range [" + capabilities.minProtocolVersion() + ","
                    + capabilities.maxProtocolVersion() + "] has no overlap with coordinator range ["
                    + Protocol.MIN_SUPPORTED_VERSION + "," + Protocol.VERSION + "]");
        }
        return upper;
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
