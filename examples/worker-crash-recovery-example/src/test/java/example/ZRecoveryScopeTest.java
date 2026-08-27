package example;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Independent lifecycle scope that must continue on a replacement worker. */
@TestMethodOrder(MethodOrderer.MethodName.class)
class ZRecoveryScopeTest {
    @Test
    void b_runsAfterReplacement() {
        assertTrue(true);
    }

    @Test
    void c_alsoRunsAfterReplacement() {
        assertTrue(true);
    }
}
