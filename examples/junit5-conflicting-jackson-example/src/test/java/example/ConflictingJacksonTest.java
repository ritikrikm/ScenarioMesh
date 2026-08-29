package example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictingJacksonTest {
    @Test
    void targetRuntimeStillExecutesNormally() {
        assertTrue(true);
    }
}
