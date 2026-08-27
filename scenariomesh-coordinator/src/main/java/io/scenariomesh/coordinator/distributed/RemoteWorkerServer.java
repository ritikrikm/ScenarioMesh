package io.scenariomesh.coordinator.distributed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.protocol.Protocol.Envelope;
import io.scenariomesh.workerruntime.JsonCodec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Coordinator-side TCP registration endpoint for workers launched on Jenkins agents or other hosts.
 * Jenkins remains responsible for allocating nodes/executors; this server only authenticates and
 * exposes the capacity that those already-allocated worker processes register.
 */
public final class RemoteWorkerServer implements AutoCloseable {
    private final ObjectMapper mapper = JsonCodec.create();
    private final WorkerRegistrationValidator validator;
    private final RemoteWorkerDirectory directory;
    private final ServerSocket server;
    private final String token;

    public RemoteWorkerServer(InetAddress bindAddress, int port,
                              WorkerRegistrationValidator validator,
                              RemoteWorkerDirectory directory) throws Exception {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.token = UUID.randomUUID().toString();
        this.server = new ServerSocket();
        server.bind(new InetSocketAddress(Objects.requireNonNull(bindAddress, "bindAddress"), port));
    }

    public String token() {
        return token;
    }

    public InetSocketAddress address() {
        return (InetSocketAddress) server.getLocalSocketAddress();
    }

    /**
     * Waits for the next valid registration. Invalid or duplicate registrations are rejected and
     * the server continues until the supplied startup timeout expires.
     */
    public RemoteWorkerSession accept(Duration startupTimeout) throws Exception {
        Objects.requireNonNull(startupTimeout, "startupTimeout");
        if (startupTimeout.isZero() || startupTimeout.isNegative()) {
            throw new IllegalArgumentException("startupTimeout must be greater than zero");
        }
        long deadlineNanos = System.nanoTime() + startupTimeout.toNanos();
        for (;;) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) throw new java.net.SocketTimeoutException("remote worker registration timeout");
            server.setSoTimeout(Math.toIntExact(Math.max(1L,
                    Duration.ofNanos(remainingNanos).toMillis())));
            Socket socket = server.accept();
            try {
                Envelope hello = readHello(socket, Duration.ofNanos(remainingNanos));
                RemoteWorkerRegistration registration = validator.requireRegistration(hello, token);
                directory.register(registration, Instant.now());
                return new RemoteWorkerSession(mapper, socket, registration);
            } catch (RuntimeException invalidRegistration) {
                closeQuietly(socket);
            } catch (Exception transportFailure) {
                closeQuietly(socket);
                if (transportFailure instanceof java.net.SocketTimeoutException) throw transportFailure;
            }
        }
    }

    public void disconnected(RemoteWorkerSession session) {
        if (session == null) return;
        directory.disconnect(session.registration().workerId());
        try {
            session.close();
        } catch (Exception ignored) {
            // Directory liveness is authoritative even if the socket already closed.
        }
    }

    private Envelope readHello(Socket socket, Duration timeout) throws Exception {
        int originalTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(Math.toIntExact(Math.max(1L, timeout.toMillis())));
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) throw new IllegalArgumentException("remote worker disconnected before HELLO");
            return mapper.readValue(line, Envelope.class);
        } finally {
            try {
                socket.setSoTimeout(originalTimeout);
            } catch (Exception ignored) {
                // Socket may already be closed.
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (Exception ignored) {
            // Best effort rejection cleanup.
        }
    }

    @Override
    public void close() throws Exception {
        server.close();
    }
}
