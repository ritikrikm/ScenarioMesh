package example;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.MethodName.class)
class WorkerCrashRecoveryTest {

    @Test
    void a_crashWorker() {
        Runtime.getRuntime().halt(23);
    }

    @Test
    void b_runsAfterReplacement() {
        assertTrue(true);
    }

    @Test
    void c_alsoRunsAfterReplacement() {
        assertTrue(true);
    }
}
