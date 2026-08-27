package io.scenariomesh.workerruntime;

import io.scenariomesh.protocol.Protocol.Envelope;
import io.scenariomesh.protocol.Protocol.WorkerTelemetry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Emits authority-free worker presence heartbeats while a remote session is connected. */
final class PresenceHeartbeatEmitter implements AutoCloseable {
    static final Duration INTERVAL = Duration.ofSeconds(5);
    private final ScheduledExecutorService executor;

    private PresenceHeartbeatEmitter(String workerId,
                                     Supplier<WorkerTelemetry> telemetry,
                                     EnvelopeWriter writer) {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "scenariomesh-presence-" + workerId);
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(() -> {
            try { writer.write(Envelope.presence(workerId, telemetry.get())); }
            catch (Exception ignored) {
                // The command/result socket path is authoritative for disconnect handling.
            }
        }, INTERVAL.toMillis(), INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    static PresenceHeartbeatEmitter start(String workerId,
                                          Supplier<WorkerTelemetry> telemetry,
                                          EnvelopeWriter writer) {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(telemetry, "telemetry");
        Objects.requireNonNull(writer, "writer");
        return new PresenceHeartbeatEmitter(workerId, telemetry, writer);
    }

    @Override public void close() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    interface EnvelopeWriter { void write(Envelope envelope) throws Exception; }
}
