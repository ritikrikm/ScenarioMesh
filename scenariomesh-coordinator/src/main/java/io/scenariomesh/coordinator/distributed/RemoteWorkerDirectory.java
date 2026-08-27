package io.scenariomesh.coordinator.distributed;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Coordinator-owned inventory of remotely hosted worker capacity. */
public final class RemoteWorkerDirectory {
    private final Duration heartbeatTimeout;
    private final Map<String, Entry> workers = new LinkedHashMap<>();

    public RemoteWorkerDirectory(Duration heartbeatTimeout) {
        this.heartbeatTimeout = Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
        if (heartbeatTimeout.isZero() || heartbeatTimeout.isNegative()) {
            throw new IllegalArgumentException("heartbeatTimeout must be greater than zero");
        }
    }

    public synchronized void register(RemoteWorkerRegistration registration, Instant now) {
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(now, "now");
        workers.put(registration.workerId(), new Entry(registration, now, 0, false));
    }

    public synchronized void heartbeat(String workerId, Instant now) {
        Objects.requireNonNull(now, "now");
        Entry current = required(workerId);
        workers.put(workerId, new Entry(current.registration(), now, current.busySlots(), current.draining()));
    }

    public synchronized void beginDrain(String workerId) {
        Entry current = required(workerId);
        workers.put(workerId, new Entry(current.registration(), current.lastHeartbeat(), current.busySlots(), true));
    }

    public synchronized void claimSlot(String workerId, Instant now) {
        Entry current = live(workerId, now);
        if (current.draining()) throw new IllegalStateException("remote worker is draining: " + workerId);
        if (current.busySlots() >= current.registration().slots()) {
            throw new IllegalStateException("remote worker has no free slots: " + workerId);
        }
        workers.put(workerId, new Entry(current.registration(), current.lastHeartbeat(), current.busySlots() + 1, false));
    }

    public synchronized void releaseSlot(String workerId) {
        Entry current = required(workerId);
        if (current.busySlots() < 1) throw new IllegalStateException("remote worker has no claimed slots: " + workerId);
        workers.put(workerId, new Entry(current.registration(), current.lastHeartbeat(), current.busySlots() - 1, current.draining()));
    }

    /** Returns compatible live workers, preferring the host with the most currently free slots. */
    public synchronized List<RemoteWorkerRegistration> eligible(String runtimeFingerprint,
                                                                 String adapterId,
                                                                 String engineId,
                                                                 Instant now) {
        Objects.requireNonNull(now, "now");
        List<Entry> eligible = new ArrayList<>();
        for (Entry entry : workers.values()) {
            if (entry.draining() || stale(entry, now)) continue;
            if (entry.busySlots() >= entry.registration().slots()) continue;
            if (!entry.registration().canRun(runtimeFingerprint, adapterId, engineId)) continue;
            eligible.add(entry);
        }
        eligible.sort(Comparator
                .comparingInt((Entry entry) -> entry.registration().slots() - entry.busySlots()).reversed()
                .thenComparing(entry -> entry.registration().workerId()));
        return eligible.stream().map(Entry::registration).toList();
    }

    public synchronized List<String> staleWorkers(Instant now) {
        Objects.requireNonNull(now, "now");
        return workers.values().stream()
                .filter(entry -> stale(entry, now))
                .map(entry -> entry.registration().workerId())
                .sorted()
                .toList();
    }

    public synchronized void remove(String workerId) {
        workers.remove(workerId);
    }

    public synchronized int registeredWorkers() { return workers.size(); }

    private Entry live(String workerId, Instant now) {
        Entry entry = required(workerId);
        if (stale(entry, now)) throw new IllegalStateException("remote worker heartbeat is stale: " + workerId);
        return entry;
    }

    private Entry required(String workerId) {
        Entry entry = workers.get(workerId);
        if (entry == null) throw new IllegalArgumentException("unknown remote worker: " + workerId);
        return entry;
    }

    private boolean stale(Entry entry, Instant now) {
        return !now.isBefore(entry.lastHeartbeat().plus(heartbeatTimeout));
    }

    private record Entry(RemoteWorkerRegistration registration, Instant lastHeartbeat,
                         int busySlots, boolean draining) {}
}
