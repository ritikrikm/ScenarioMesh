package example;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class ParallelSmokeTest {
    @Test void one() throws Exception { Thread.sleep(150); }
    @Test void two() throws Exception { Thread.sleep(150); }
    @Test void three() throws Exception { Thread.sleep(150); }
    @Test void four() throws Exception { Thread.sleep(150); }
    @Test void five() throws Exception { Thread.sleep(150); }
    @Test void six() throws Exception { Thread.sleep(150); }

    @Test
    void seven_isAbortedByAssumption() {
        Assumptions.assumeTrue(false, "intentional hardening fixture");
    }

    @Disabled("intentional hardening fixture")
    @Test
    void eight_isDisabled() {
        throw new AssertionError("disabled test must never execute");
    }
}
