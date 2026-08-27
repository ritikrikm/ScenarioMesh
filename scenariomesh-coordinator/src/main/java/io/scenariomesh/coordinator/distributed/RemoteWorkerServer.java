package io.scenariomesh.coordinator.distributed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.config.TlsConfig;
import io.scenariomesh.config.TlsContextFactory;
import io.scenariomesh.protocol.Protocol.Envelope;
import io.scenariomesh.workerruntime.JsonCodec;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
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

/** Authenticated coordinator endpoint for workers allocated by Jenkins or another CI orchestrator. */
public final class RemoteWorkerServer implements AutoCloseable {
    private final ObjectMapper mapper = JsonCodec.create();
    private final WorkerRegistrationValidator validator;
    private final RemoteWorkerDirectory directory;
    private final ServerSocket server;
    private final String token;
    private final boolean tls;

    public RemoteWorkerServer(InetAddress bindAddress, int port,
                              WorkerRegistrationValidator validator,
                              RemoteWorkerDirectory directory) throws Exception {
        this(bindAddress, port, UUID.randomUUID().toString(), validator, directory, TlsConfig.disabled());
    }

    public RemoteWorkerServer(InetAddress bindAddress, int port, String token,
                              WorkerRegistrationValidator validator,
                              RemoteWorkerDirectory directory) throws Exception {
        this(bindAddress, port, token, validator, directory, TlsConfig.disabled());
    }

    public RemoteWorkerServer(InetAddress bindAddress, int port, String token,
                              WorkerRegistrationValidator validator,
                              RemoteWorkerDirectory directory,
                              TlsConfig tlsConfig) throws Exception {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.directory = Objects.requireNonNull(directory, "directory");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("remote worker token must not be blank");
        this.token = token.trim();
        TlsConfig effectiveTls = tlsConfig == null ? TlsConfig.disabled() : tlsConfig;
        this.tls = effectiveTls.enabled();
        if (effectiveTls.enabled()) {
            SSLServerSocket ssl = (SSLServerSocket) TlsContextFactory.create(effectiveTls)
                    .getServerSocketFactory().createServerSocket();
            ssl.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            ssl.setNeedClientAuth(effectiveTls.requireClientAuth());
            this.server = ssl;
        } else {
            this.server = new ServerSocket();
        }
        server.bind(new InetSocketAddress(Objects.requireNonNull(bindAddress, "bindAddress"), port));
    }

    public String token() { return token; }
    public boolean tlsEnabled() { return tls; }
    public InetSocketAddress address() { return (InetSocketAddress) server.getLocalSocketAddress(); }

    public RemoteWorkerSession accept(Duration startupTimeout) throws Exception {
        Objects.requireNonNull(startupTimeout, "startupTimeout");
        if (startupTimeout.isZero() || startupTimeout.isNegative()) {
            throw new IllegalArgumentException("startupTimeout must be greater than zero");
        }
        long deadlineNanos = System.nanoTime() + startupTimeout.toNanos();
        for (;;) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) throw new java.net.SocketTimeoutException("remote worker registration timeout");
            server.setSoTimeout(Math.toIntExact(Math.max(1L, Duration.ofNanos(remainingNanos).toMillis())));
            Socket socket = server.accept();
            try {
                if (socket instanceof SSLSocket sslSocket) {
                    sslSocket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                    sslSocket.setSoTimeout(Math.toIntExact(Math.max(1L, Duration.ofNanos(remainingNanos).toMillis())));
                    sslSocket.startHandshake();
                }
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
        directory.remove(session.registration().workerId());
        try { session.close(); } catch (Exception ignored) { }
    }

    private Envelope readHello(Socket socket, Duration timeout) throws Exception {
        int originalTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(Math.toIntExact(Math.max(1L, timeout.toMillis())));
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) throw new IllegalArgumentException("remote worker disconnected before HELLO");
            return mapper.readValue(line, Envelope.class);
        } finally {
            try { socket.setSoTimeout(originalTimeout); } catch (Exception ignored) { }
        }
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (Exception ignored) { }
    }

    @Override public void close() throws Exception { server.close(); }
}
