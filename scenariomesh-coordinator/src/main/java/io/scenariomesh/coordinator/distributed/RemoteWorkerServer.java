package io.scenariomesh.coordinator.distributed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.config.TlsConfig;
import io.scenariomesh.config.TlsContextFactory;
import io.scenariomesh.protocol.Protocol.Envelope;
import io.scenariomesh.protocol.Protocol.WorkerCapabilities;
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
    private static final String TRACE_PREFIX = "[ScenarioMesh][REMOTE HANDSHAKE]";

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
        trace("LISTEN address=" + server.getLocalSocketAddress() + " tls=" + tls);
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
            if (remainingNanos <= 0) {
                trace("REGISTRATION_TIMEOUT address=" + server.getLocalSocketAddress());
                throw new java.net.SocketTimeoutException("remote worker registration timeout");
            }
            server.setSoTimeout(Math.toIntExact(Math.max(1L, Duration.ofNanos(remainingNanos).toMillis())));
            Socket socket = server.accept();
            trace("TCP_ACCEPT remote=" + socket.getRemoteSocketAddress() + " local=" + socket.getLocalSocketAddress()
                    + " tls=" + (socket instanceof SSLSocket));
            try {
                if (socket instanceof SSLSocket sslSocket) {
                    sslSocket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                    sslSocket.setSoTimeout(Math.toIntExact(Math.max(1L, Duration.ofNanos(remainingNanos).toMillis())));
                    trace("TLS_HANDSHAKE_START remote=" + socket.getRemoteSocketAddress());
                    sslSocket.startHandshake();
                    trace("TLS_HANDSHAKE_OK remote=" + socket.getRemoteSocketAddress()
                            + " protocol=" + sslSocket.getSession().getProtocol()
                            + " cipher=" + sslSocket.getSession().getCipherSuite());
                }
                Handshake handshake = readHello(socket, Duration.ofNanos(remainingNanos));
                traceHello(handshake.hello(), socket);
                RemoteWorkerRegistration registration = validator.requireRegistration(handshake.hello(), token);
                int negotiated = WorkerRegistrationValidator.negotiatedProtocolVersion(registration);
                boolean negotiationAware = WorkerRegistrationValidator.negotiationAware(registration);
                trace("REGISTRATION_VALID worker=" + registration.workerId()
                        + " negotiatedProtocol=" + negotiated
                        + " negotiationAware=" + negotiationAware
                        + " java=" + registration.javaFeature()
                        + " slots=" + registration.slots()
                        + " adapters=" + registration.adapterIds()
                        + " engines=" + registration.engineIds());
                RemoteWorkerSession session = new RemoteWorkerSession(mapper, socket, registration, handshake.reader());
                if (negotiationAware) {
                    trace("NEGOTIATION_ACK_SEND worker=" + registration.workerId() + " protocol=" + negotiated);
                    session.write(Envelope.ack(registration.workerId()));
                } else {
                    trace("LEGACY_SESSION_NO_ACK worker=" + registration.workerId() + " protocol=" + negotiated);
                }
                directory.register(registration, Instant.now());
                trace("DIRECTORY_REGISTERED worker=" + registration.workerId() + " protocol=" + negotiated);
                return session;
            } catch (RuntimeException invalidRegistration) {
                trace("REGISTRATION_REJECT remote=" + socket.getRemoteSocketAddress()
                        + " exception=" + invalidRegistration.getClass().getName()
                        + " message=" + safeMessage(invalidRegistration));
                closeQuietly(socket);
            } catch (Exception transportFailure) {
                trace("REGISTRATION_TRANSPORT_FAILURE remote=" + socket.getRemoteSocketAddress()
                        + " exception=" + transportFailure.getClass().getName()
                        + " message=" + safeMessage(transportFailure));
                closeQuietly(socket);
                if (transportFailure instanceof java.net.SocketTimeoutException) throw transportFailure;
            }
        }
    }

    public void disconnected(RemoteWorkerSession session) {
        if (session == null) return;
        trace("DISCONNECT worker=" + session.registration().workerId() + " protocol=" + session.protocolVersion());
        directory.remove(session.registration().workerId());
        try { session.close(); } catch (Exception ignored) { }
    }

    private Handshake readHello(Socket socket, Duration timeout) throws Exception {
        int originalTimeout = socket.getSoTimeout();
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        try {
            socket.setSoTimeout(Math.toIntExact(Math.max(1L, timeout.toMillis())));
            trace("HELLO_WAIT remote=" + socket.getRemoteSocketAddress() + " timeoutMs=" + Math.max(1L, timeout.toMillis()));
            String line = reader.readLine();
            if (line == null) throw new IllegalArgumentException("remote worker disconnected before HELLO");
            Envelope hello = mapper.readValue(line, Envelope.class);
            return new Handshake(hello, reader);
        } finally {
            try { socket.setSoTimeout(originalTimeout); } catch (Exception ignored) { }
        }
    }

    private static void traceHello(Envelope hello, Socket socket) {
        WorkerCapabilities capabilities = hello.capabilities();
        String range = capabilities == null ? "none"
                : (capabilities.advertisesProtocolRange()
                    ? "[" + capabilities.minProtocolVersion() + "," + capabilities.maxProtocolVersion() + "]"
                    : "legacy-unadvertised");
        trace("HELLO_RECEIVED remote=" + socket.getRemoteSocketAddress()
                + " worker=" + hello.workerId()
                + " bootstrapProtocol=" + hello.protocolVersion()
                + " type=" + hello.type()
                + " capabilitiesPresent=" + (capabilities != null)
                + " advertisedRange=" + range
                + " java=" + (capabilities == null ? "-" : capabilities.javaFeature())
                + " slots=" + (capabilities == null ? "-" : capabilities.slots())
                + " adapters=" + (capabilities == null ? "-" : capabilities.adapterIds())
                + " engines=" + (capabilities == null ? "-" : capabilities.engineIds()));
    }

    private record Handshake(Envelope hello, BufferedReader reader) { }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static void trace(String message) {
        System.err.println(TRACE_PREFIX + " " + Instant.now() + " thread=" + Thread.currentThread().getName() + " " + message);
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (Exception ignored) { }
    }

    @Override public void close() throws Exception {
        trace("SERVER_CLOSE address=" + server.getLocalSocketAddress());
        server.close();
    }
}
