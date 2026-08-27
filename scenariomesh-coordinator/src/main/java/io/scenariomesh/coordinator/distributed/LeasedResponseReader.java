package io.scenariomesh.coordinator.distributed;

import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Reads one terminal worker response while consuming lease and presence heartbeats internally. */
public final class LeasedResponseReader {
    private final DistributedWorkAuthority authority;
    private final Supplier<Instant> wallClock;

    public LeasedResponseReader(DistributedWorkAuthority authority) { this(authority, Instant::now); }

    LeasedResponseReader(DistributedWorkAuthority authority, Supplier<Instant> wallClock) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
    }

    public Envelope readTerminal(String workerId, Duration timeout, TimedEnvelopeReader reader) throws Exception {
        return readTerminal(workerId, timeout, reader, ignored -> { });
    }

    /**
     * Presence proves only socket/process liveness. Lease heartbeats additionally renew authoritative
     * work ownership. Both refresh the worker directory only after worker identity validation.
     */
    public Envelope readTerminal(String workerId, Duration timeout, TimedEnvelopeReader reader,
                                 Consumer<Instant> livenessObserver) throws Exception {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(livenessObserver, "livenessObserver");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be greater than zero");

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        for (;;) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) throw new java.net.SocketTimeoutException("worker response timeout");
            Envelope envelope = reader.read(Duration.ofNanos(remainingNanos));
            if (envelope == null) return null;
            if (!Objects.equals(workerId, envelope.workerId())) {
                throw new IllegalArgumentException("worker response identity mismatch: expected " + workerId
                        + " but received " + envelope.workerId());
            }
            Instant heartbeatAt = wallClock.get();
            if (envelope.type() == Protocol.Type.PRESENCE) {
                livenessObserver.accept(heartbeatAt);
                continue;
            }
            if (envelope.type() == Protocol.Type.HEARTBEAT) {
                authority.heartbeat(workerId, envelope, heartbeatAt);
                livenessObserver.accept(heartbeatAt);
                continue;
            }
            return envelope;
        }
    }

    @FunctionalInterface
    public interface TimedEnvelopeReader {
        Envelope read(Duration timeout) throws Exception;
    }
}
