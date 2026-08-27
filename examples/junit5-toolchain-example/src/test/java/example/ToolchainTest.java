package example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolchainTest {
    @Test void runsOnSelectedToolchain() {
        assertTrue(Runtime.version().feature() >= 17);
    }
}
