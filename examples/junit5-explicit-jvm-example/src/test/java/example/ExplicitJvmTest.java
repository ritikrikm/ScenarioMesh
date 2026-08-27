package example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplicitJvmTest {
    @Test void runsInsideConfiguredJvm() {
        assertTrue(Runtime.version().feature() >= 17);
    }
}
