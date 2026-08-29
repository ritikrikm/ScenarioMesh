package io.scenariomesh.coordinator.distributed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.protocol.Protocol.Envelope;
import io.scenariomesh.protocol.ProtocolFrameReader;

import java.io.BufferedWriter;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** One authenticated remote worker session owned by exactly one coordinator reader lane. */
public final class RemoteWorkerSession implements AutoCloseable {
    private static final String TRACE_PREFIX = "[ScenarioMesh][REMOTE TRACE]";

    private final ObjectMapper mapper;
    private final Socket socket;
    private final ProtocolFrameReader reader;
    private final BufferedWriter writer;
    private final RemoteWorkerRegistration registration;
    private final int protocolVersion;

    RemoteWorkerSession(ObjectMapper mapper, Socket socket, RemoteWorkerRegistration registration) throws Exception {
        this(mapper, socket, registration,
                new ProtocolFrameReader(socket.getInputStream()));
    }

    RemoteWorkerSession(ObjectMapper mapper, Socket socket, RemoteWorkerRegistration registration,
                        ProtocolFrameReader reader) throws Exception {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.socket = Objects.requireNonNull(socket, "socket");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.protocolVersion = WorkerRegistrationValidator.negotiatedProtocolVersion(registration);
        this.reader = Objects.requireNonNull(reader, "reader");
        this.writer = new BufferedWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8));
        trace("SESSION_OPEN worker=" + registration.workerId()
                + " protocol=" + protocolVersion
                + " negotiationAware=" + WorkerRegistrationValidator.negotiationAware(registration)
                + " java=" + registration.javaFeature()
                + " slots=" + registration.slots()
                + " adapters=" + registration.adapterIds()
                + " engines=" + registration.engineIds()
                + " remote=" + socket.getRemoteSocketAddress()
                + " local=" + socket.getLocalSocketAddress());
    }

    public RemoteWorkerRegistration registration() { return registration; }
    public int protocolVersion() { return protocolVersion; }

    public synchronized void write(Envelope envelope) throws Exception {
        Envelope versioned = Objects.requireNonNull(envelope, "envelope").withProtocolVersion(protocolVersion);
        versioned.validatePayloadShape();
        traceEnvelope("OUT", versioned);
        try {
            writer.write(mapper.writeValueAsString(versioned));
            writer.newLine();
            writer.flush();
        } catch (Exception exception) {
            trace("WRITE_FAILURE worker=" + registration.workerId() + " protocol=" + protocolVersion
                    + " exception=" + exception.getClass().getName() + " message=" + safeMessage(exception));
            throw exception;
        }
    }

    public Envelope read(Duration timeout) throws Exception {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be greater than zero");
        int originalTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(Math.toIntExact(Math.max(1L, timeout.toMillis())));
            byte[] frame = reader.readBlocking();
            if (frame == null) {
                trace("IN EOF worker=" + registration.workerId() + " protocol=" + protocolVersion);
                return null;
            }
            Envelope envelope = mapper.readValue(frame, Envelope.class);
            traceEnvelope("IN", envelope);
            return requireValidSessionEnvelope(envelope);
        } catch (Exception exception) {
            trace("READ_FAILURE worker=" + registration.workerId() + " expectedProtocol=" + protocolVersion
                    + " exception=" + exception.getClass().getName() + " message=" + safeMessage(exception));
            throw exception;
        } finally {
            try { socket.setSoTimeout(originalTimeout); } catch (Exception ignored) { }
        }
    }

    /** Non-blocking read used only by the owning scheduler lane to consume queued idle presence. */
    public Envelope readAvailable() throws Exception {
        try {
            byte[] frame = reader.readAvailable();
            if (frame == null) return null;
            Envelope envelope = mapper.readValue(frame, Envelope.class);
            traceEnvelope("IN_AVAILABLE", envelope);
            return requireValidSessionEnvelope(envelope);
        } catch (Exception exception) {
            trace("READ_AVAILABLE_FAILURE worker=" + registration.workerId() + " expectedProtocol=" + protocolVersion
                    + " exception=" + exception.getClass().getName() + " message=" + safeMessage(exception));
            throw exception;
        }
    }

    private Envelope requireValidSessionEnvelope(Envelope envelope) {
        requireSessionVersion(envelope);
        envelope.validatePayloadShape();
        if (!registration.workerId().equals(envelope.workerId())) {
            throw new IllegalArgumentException("remote worker changed authenticated worker identity from "
                    + registration.workerId() + " to " + envelope.workerId());
        }
        return envelope;
    }

    private void requireSessionVersion(Envelope envelope) {
        if (envelope.protocolVersion() != protocolVersion) {
            trace("PROTOCOL_MISMATCH worker=" + registration.workerId() + " expected=" + protocolVersion
                    + " actual=" + envelope.protocolVersion() + " type=" + envelope.type());
            throw new IllegalArgumentException("remote worker changed negotiated protocol version from "
                    + protocolVersion + " to " + envelope.protocolVersion());
        }
    }

    private void traceEnvelope(String direction, Envelope envelope) {
        trace(direction + " worker=" + registration.workerId()
                + " type=" + envelope.type()
                + " protocol=" + envelope.protocolVersion()
                + " envelopeWorker=" + envelope.workerId()
                + " workUnit=" + value(envelope.workUnitId())
                + " lease=" + value(envelope.leaseId())
                + " attempt=" + envelope.attempt()
                + " tasks=" + (envelope.tasks() == null ? "null" : envelope.tasks().size())
                + " results=" + (envelope.results() == null ? "null" : envelope.results().size()));
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static void trace(String message) {
        System.err.println(TRACE_PREFIX + " " + Instant.now() + " thread=" + Thread.currentThread().getName() + " " + message);
    }

    public boolean connected() { return socket.isConnected() && !socket.isClosed(); }

    @Override public void close() throws Exception {
        trace("SESSION_CLOSE worker=" + registration.workerId() + " protocol=" + protocolVersion
                + " connected=" + connected());
        socket.close();
    }
}
