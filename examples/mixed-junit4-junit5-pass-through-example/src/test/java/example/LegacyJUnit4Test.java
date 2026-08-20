package example;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class LegacyJUnit4Test {
    @Test
    public void vintageRunsThroughNativeSurefire() {
        assertTrue(true);
    }
}
