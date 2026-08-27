package example;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

class ParallelSmokeTest {
    private static final Path LIFECYCLE_LOG = Path.of("target", "junit-lifecycle.log");

    @BeforeAll
    static void beforeAll() throws Exception {
        Files.createDirectories(LIFECYCLE_LOG.getParent());
        Files.writeString(LIFECYCLE_LOG, "BEFORE_ALL\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @AfterAll
    static void afterAll() throws Exception {
        Files.writeString(LIFECYCLE_LOG, "AFTER_ALL\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

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
