package example;

import org.testng.SkipException;
import org.testng.annotations.Test;

public class ParallelSmokeTest {
    @Test public void one() throws Exception { Thread.sleep(150); }
    @Test public void two() throws Exception { Thread.sleep(150); }
    @Test public void three() throws Exception { Thread.sleep(150); }
    @Test public void four() throws Exception { Thread.sleep(150); }
    @Test public void five() throws Exception { Thread.sleep(150); }
    @Test public void six() throws Exception { Thread.sleep(150); }

    @Test
    public void seven_isSkippedAtRuntime() {
        throw new SkipException("intentional hardening fixture");
    }

    @Test(enabled = false)
    public void eight_isDisabled() {
        throw new AssertionError("disabled test must never execute");
    }
}
