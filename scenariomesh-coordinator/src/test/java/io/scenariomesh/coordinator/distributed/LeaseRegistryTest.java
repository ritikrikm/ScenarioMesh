package io.scenariomesh.coordinator.distributed;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaseRegistryTest {
    private final Instant start = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void heartbeatRenewsOnlyAuthoritativeWorkerLease() {
        LeaseRegistry registry = new LeaseRegistry(Duration.ofSeconds(30));
        WorkLease lease = registry.issue("scope:A", "worker-a", 1, List.of("a", "b"), start);

        WorkLease renewed = registry.heartbeat("scope:A", lease.leaseId(), "worker-a", start.plusSeconds(20));

        assertEquals(start.plusSeconds(50), renewed.expiresAt());
        assertThrows(LeaseRegistry.StaleLeaseException.class,
                () -> registry.heartbeat("scope:A", lease.leaseId(), "worker-b", start.plusSeconds(21)));
    }

    @Test
    void expiredAgentWorkCanBeRescheduledAndLateResultIsRejected() {
        LeaseRegistry registry = new LeaseRegistry(Duration.ofSeconds(30));
        WorkLease old = registry.issue("scope:A", "worker-a", 1, List.of("a", "b"), start);

        assertEquals(List.of("scope:A"), registry.expire(start.plusSeconds(31)));
        WorkLease replacement = registry.issue("scope:A", "worker-b", 2, List.of("a", "b"), start.plusSeconds(31));

        assertThrows(LeaseRegistry.StaleLeaseException.class,
                () -> registry.acceptResult("scope:A", old.leaseId(), "worker-a", start.plusSeconds(32)));
        WorkLease accepted = registry.acceptResult("scope:A", replacement.leaseId(), "worker-b", start.plusSeconds(32));
        assertEquals(2, accepted.attempt());
        assertEquals(0, registry.activeLeaseCount());
    }

    @Test
    void replacingLeaseBeforeExpiryImmediatelyRevokesOldAuthority() {
        LeaseRegistry registry = new LeaseRegistry(Duration.ofMinutes(1));
        WorkLease old = registry.issue("scenario:x", "worker-a", 1, List.of("x"), start);
        WorkLease replacement = registry.issue("scenario:x", "worker-b", 2, List.of("x"), start.plusSeconds(5));

        assertThrows(LeaseRegistry.StaleLeaseException.class,
                () -> registry.acceptResult("scenario:x", old.leaseId(), "worker-a", start.plusSeconds(6)));
        assertEquals(replacement.leaseId(),
                registry.acceptResult("scenario:x", replacement.leaseId(), "worker-b", start.plusSeconds(6)).leaseId());
    }

    @Test
    void workerLossImmediatelyFencesEveryOwnedLeaseWithoutWaitingForExpiry() {
        LeaseRegistry registry = new LeaseRegistry(Duration.ofMinutes(5));
        WorkLease first = registry.issue("unit-a", "worker-a", 1, List.of("a"), start);
        WorkLease second = registry.issue("unit-b", "worker-a", 1, List.of("b"), start);
        WorkLease other = registry.issue("unit-c", "worker-b", 1, List.of("c"), start);

        assertEquals(List.of("unit-a", "unit-b"), registry.revokeWorker("worker-a"));
        assertEquals(1, registry.activeLeaseCount());
        assertThrows(LeaseRegistry.StaleLeaseException.class,
                () -> registry.acceptResult("unit-a", first.leaseId(), "worker-a", start.plusSeconds(1)));
        assertThrows(LeaseRegistry.StaleLeaseException.class,
                () -> registry.heartbeat("unit-b", second.leaseId(), "worker-a", start.plusSeconds(1)));
        assertEquals(other.leaseId(),
                registry.acceptResult("unit-c", other.leaseId(), "worker-b", start.plusSeconds(1)).leaseId());
    }

    @Test
    void remoteWorkerCapabilitiesRequireExactRuntimeFingerprint() {
        RemoteWorkerRegistration worker = new RemoteWorkerRegistration(
                "worker-a", "sha256:runtime-1", 6, 21, "Linux", "amd64",
                java.util.Set.of("junit-platform"), java.util.Set.of("junit-jupiter"),
                java.util.Map.of("jenkins.agent", "linux-large"));

        assertTrue(worker.canRun("sha256:runtime-1", "junit-platform", "junit-jupiter"));
        assertTrue(!worker.canRun("sha256:different", "junit-platform", "junit-jupiter"));
        assertTrue(!worker.canRun("sha256:runtime-1", "testng", null));
    }
}
