package example;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.MethodName.class)
class TestFailureContinuationTest {

    @Test
    void a_regularAssertionFailure() {
        assertEquals("expected", "actual");
    }

    @Test
    void b_sameWorkerContinues() {
        assertTrue(true);
    }

    @Test
    void c_sameWorkerStillContinues() {
        assertTrue(true);
    }
}
