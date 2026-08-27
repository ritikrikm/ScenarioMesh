package io.scenariomesh.coordinator.distributed;

import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reads one terminal worker response while consuming lease heartbeats internally.
 * The timeout remains a hard upper bound for the work-unit response; heartbeats renew
 * distributed authority but do not silently extend the configured task timeout.
 */
public final class LeasedResponseReader {
    private final DistributedWorkAuthority authority;
    private final Supplier<Instant> wallClock;

    public LeasedResponseReader(DistributedWorkAuthority authority) {
        this(authority, Instant::now);
    }

    LeasedResponseReader(DistributedWorkAuthority authority, Supplier<Instant> wallClock) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
    }

    public Envelope readTerminal(String workerId, Duration timeout, TimedEnvelopeReader reader) throws Exception {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(reader, "reader");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be greater than zero");
        }

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        for (;;) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) throw new java.net.SocketTimeoutException("worker response timeout");
            Envelope envelope = reader.read(Duration.ofNanos(remainingNanos));
            if (envelope == null) return null;
            if (envelope.type() != Protocol.Type.HEARTBEAT) return envelope;
            authority.heartbeat(workerId, envelope, wallClock.get());
        }
    }

    @FunctionalInterface
    public interface TimedEnvelopeReader {
        Envelope read(Duration timeout) throws Exception;
    }
}
