package example;

import org.junit.jupiter.api.Test;

class ParallelSmokeTest {
    @Test void one() throws Exception { Thread.sleep(150); }
    @Test void two() throws Exception { Thread.sleep(150); }
    @Test void three() throws Exception { Thread.sleep(150); }
    @Test void four() throws Exception { Thread.sleep(150); }
    @Test void five() throws Exception { Thread.sleep(150); }
    @Test void six() throws Exception { Thread.sleep(150); }
}
