package io.scenariomesh.coordinator.distributed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.protocol.Protocol.Envelope;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** One authenticated remote worker session owned by exactly one coordinator reader lane. */
public final class RemoteWorkerSession implements AutoCloseable {
    private final ObjectMapper mapper;
    private final Socket socket;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final RemoteWorkerRegistration registration;
    private final int protocolVersion;

    RemoteWorkerSession(ObjectMapper mapper, Socket socket, RemoteWorkerRegistration registration) throws Exception {
        this(mapper, socket, registration,
                new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)));
    }

    RemoteWorkerSession(ObjectMapper mapper, Socket socket, RemoteWorkerRegistration registration,
                        BufferedReader reader) throws Exception {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.socket = Objects.requireNonNull(socket, "socket");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.protocolVersion = WorkerRegistrationValidator.negotiatedProtocolVersion(registration);
        this.reader = Objects.requireNonNull(reader, "reader");
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    public RemoteWorkerRegistration registration() { return registration; }
    public int protocolVersion() { return protocolVersion; }

    public synchronized void write(Envelope envelope) throws Exception {
        Envelope versioned = Objects.requireNonNull(envelope, "envelope").withProtocolVersion(protocolVersion);
        writer.write(mapper.writeValueAsString(versioned));
        writer.newLine();
        writer.flush();
    }

    public Envelope read(Duration timeout) throws Exception {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be greater than zero");
        int originalTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(Math.toIntExact(Math.max(1L, timeout.toMillis())));
            String line = reader.readLine();
            return line == null ? null : requireSessionVersion(mapper.readValue(line, Envelope.class));
        } finally {
            try { socket.setSoTimeout(originalTimeout); } catch (Exception ignored) { }
        }
    }

    /** Non-blocking read used only by the owning scheduler lane to consume queued idle presence. */
    public Envelope readAvailable() throws Exception {
        if (!reader.ready()) return null;
        String line = reader.readLine();
        return line == null ? null : requireSessionVersion(mapper.readValue(line, Envelope.class));
    }

    private Envelope requireSessionVersion(Envelope envelope) {
        if (envelope.protocolVersion() != protocolVersion) {
            throw new IllegalArgumentException("remote worker changed negotiated protocol version from "
                    + protocolVersion + " to " + envelope.protocolVersion());
        }
        return envelope;
    }

    public boolean connected() { return socket.isConnected() && !socket.isClosed(); }

    @Override public void close() throws Exception { socket.close(); }
}
