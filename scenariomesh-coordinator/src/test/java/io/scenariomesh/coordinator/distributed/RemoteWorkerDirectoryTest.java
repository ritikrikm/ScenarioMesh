package io.scenariomesh.coordinator.distributed;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteWorkerDirectoryTest {
    private final Instant start = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void prefersMostFreeCompatibleCapacityAndIgnoresFingerprintMismatch() {
        RemoteWorkerDirectory directory = new RemoteWorkerDirectory(Duration.ofSeconds(20));
        directory.register(worker("agent-a", "fp", 2), start);
        directory.register(worker("agent-b", "fp", 6), start);
        directory.register(worker("agent-c", "different", 20), start);

        assertEquals("agent-b", directory.eligible("fp", "junit-platform", "junit-jupiter", start).get(0).workerId());
        directory.claimSlot("agent-b", start);
        directory.claimSlot("agent-b", start);
        directory.claimSlot("agent-b", start);
        directory.claimSlot("agent-b", start);
        directory.claimSlot("agent-b", start);

        assertEquals("agent-a", directory.eligible("fp", "junit-platform", "junit-jupiter", start).get(0).workerId());
    }

    @Test
    void staleHeartbeatRemovesCapacityUntilWorkerReregistersOrHeartbeats() {
        RemoteWorkerDirectory directory = new RemoteWorkerDirectory(Duration.ofSeconds(20));
        directory.register(worker("agent-a", "fp", 4), start);

        assertEquals(1, directory.eligible("fp", "junit-platform", "junit-jupiter", start.plusSeconds(19)).size());
        assertEquals(0, directory.eligible("fp", "junit-platform", "junit-jupiter", start.plusSeconds(20)).size());
        assertEquals(java.util.List.of("agent-a"), directory.staleWorkers(start.plusSeconds(20)));

        directory.heartbeat("agent-a", start.plusSeconds(21));
        assertEquals(1, directory.eligible("fp", "junit-platform", "junit-jupiter", start.plusSeconds(22)).size());
    }

    @Test
    void drainingWorkerFinishesClaimedWorkButGetsNoNewWork() {
        RemoteWorkerDirectory directory = new RemoteWorkerDirectory(Duration.ofMinutes(1));
        directory.register(worker("agent-a", "fp", 2), start);
        directory.claimSlot("agent-a", start);
        directory.beginDrain("agent-a");

        assertEquals(0, directory.eligible("fp", "junit-platform", "junit-jupiter", start).size());
        directory.releaseSlot("agent-a");
        assertThrows(IllegalStateException.class, () -> directory.claimSlot("agent-a", start));
    }

    private RemoteWorkerRegistration worker(String id, String fingerprint, int slots) {
        return new RemoteWorkerRegistration(id, fingerprint, slots, 21, "Linux", "amd64",
                Set.of("junit-platform"), Set.of("junit-jupiter"), Map.of("jenkins.label", "linux"));
    }
}
