package example;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.MethodName.class)
class ProcessTreeCleanupTest {
    private static final Path PID_FILE = Path.of("target", "child.pid");

    @Test
    void a_startChildProcess() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                LongRunningChild.class.getName())
                .redirectErrorStream(true)
                .start();
        Files.createDirectories(PID_FILE.getParent());
        Files.writeString(PID_FILE, Long.toString(child.pid()));
        assertTrue(child.isAlive());
    }

    @Test
    void b_recycledWorkerKilledChild() throws Exception {
        long pid = Long.parseLong(Files.readString(PID_FILE).trim());
        boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        assertFalse(alive, "child process from retired worker must not survive");
    }
}
