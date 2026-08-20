package example;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.MethodName.class)
class WorkerCrashRecoveryTest {

    @Test
    void a_crashWorker() throws Exception {
        Path marker = Path.of("target", "crash-once.marker");
        Files.createDirectories(marker.getParent());
        if (Files.notExists(marker)) {
            Files.writeString(marker, "crashed");
            Runtime.getRuntime().halt(23);
        }
        assertTrue(Files.exists(marker));
    }

    @Test
    void b_runsAfterReplacement() {
        assertTrue(true);
    }

    @Test
    void c_alsoRunsAfterReplacement() {
        assertTrue(true);
    }
}
