package io.scenariomesh.workerruntime;

import io.scenariomesh.protocol.Protocol.Envelope;
import io.scenariomesh.protocol.Protocol.WorkerTelemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Emits lease heartbeats while an adapter is synchronously executing a work unit. */
final class LeaseHeartbeatEmitter implements AutoCloseable {
    private static final LeaseHeartbeatEmitter NOOP = new LeaseHeartbeatEmitter(null, null);

    private final ScheduledExecutorService executor;
    private final AtomicReference<Exception> failure;

    private LeaseHeartbeatEmitter(ScheduledExecutorService executor, AtomicReference<Exception> failure) {
        this.executor = executor;
        this.failure = failure;
    }

    static LeaseHeartbeatEmitter start(String workerId, Envelope run,
                                       Supplier<WorkerTelemetry> telemetry,
                                       CheckedEnvelopeWriter writer) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(telemetry, "telemetry");
        Objects.requireNonNull(writer, "writer");
        if (run.workUnitId() == null || run.leaseId() == null || run.leaseExpiresAt() == null) return NOOP;

        Duration remaining = Duration.between(Instant.now(), run.leaseExpiresAt());
        if (remaining.isZero() || remaining.isNegative()) return NOOP;
        long periodNanos = heartbeatInterval(remaining).toNanos();
        AtomicReference<Exception> failure = new AtomicReference<>();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "scenariomesh-heartbeat-" + workerId);
            thread.setDaemon(true);
            return thread;
        };
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(factory);
        executor.scheduleAtFixedRate(() -> {
            if (failure.get() != null) return;
            try {
                writer.write(Envelope.heartbeat(workerId, run.workUnitId(), run.leaseId(), telemetry.get()));
            } catch (Exception exception) {
                failure.compareAndSet(null, exception);
            }
        }, periodNanos, periodNanos, TimeUnit.NANOSECONDS);
        return new LeaseHeartbeatEmitter(executor, failure);
    }

    static Duration heartbeatInterval(Duration remainingLease) {
        Objects.requireNonNull(remainingLease, "remainingLease");
        if (remainingLease.isZero() || remainingLease.isNegative()) {
            throw new IllegalArgumentException("remaining lease must be greater than zero");
        }
        Duration interval = remainingLease.dividedBy(3);
        return interval.isZero() ? Duration.ofNanos(1) : interval;
    }

    void throwIfFailed() throws Exception {
        if (failure == null) return;
        Exception exception = failure.get();
        if (exception != null) throw exception;
    }

    @Override
    public void close() {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    interface CheckedEnvelopeWriter {
        void write(Envelope envelope) throws Exception;
    }
}
