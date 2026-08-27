package io.scenariomesh.workerruntime;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeaseHeartbeatEmitterTest {
    @Test
    void derivesHeartbeatCadenceFromOneThirdOfRemainingLease() {
        assertEquals(Duration.ofSeconds(10),
                LeaseHeartbeatEmitter.heartbeatInterval(Duration.ofSeconds(30)));
        assertEquals(Duration.ofNanos(1),
                LeaseHeartbeatEmitter.heartbeatInterval(Duration.ofNanos(2)));
    }

    @Test
    void rejectsExpiredLeaseForCadenceCalculation() {
        assertThrows(IllegalArgumentException.class,
                () -> LeaseHeartbeatEmitter.heartbeatInterval(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> LeaseHeartbeatEmitter.heartbeatInterval(Duration.ofSeconds(-1)));
    }
}
