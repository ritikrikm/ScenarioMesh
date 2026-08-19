package example;

import org.testng.annotations.Test;

public class ParallelSmokeTest {
    @Test public void one() throws Exception { Thread.sleep(150); }
    @Test public void two() throws Exception { Thread.sleep(150); }
    @Test public void three() throws Exception { Thread.sleep(150); }
    @Test public void four() throws Exception { Thread.sleep(150); }
    @Test public void five() throws Exception { Thread.sleep(150); }
    @Test public void six() throws Exception { Thread.sleep(150); }
}
