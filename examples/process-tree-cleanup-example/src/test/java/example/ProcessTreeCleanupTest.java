package example;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTreeCleanupTest {
    private static final Path PID_FILE = Path.of("target", "child.pid");

    @Test
    void firstIndependentScope() throws Exception {
        spawnOrVerifyRetiredChild();
    }

    @Test
    void secondIndependentScope() throws Exception {
        spawnOrVerifyRetiredChild();
    }

    private void spawnOrVerifyRetiredChild() throws Exception {
        Files.createDirectories(PID_FILE.getParent());
        if (Files.notExists(PID_FILE)) {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process child = new ProcessBuilder(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    LongRunningChild.class.getName())
                    .redirectErrorStream(true)
                    .start();
            Files.writeString(PID_FILE, Long.toString(child.pid()));
            assertTrue(child.isAlive(), "child process must be alive before the worker is recycled");
            return;
        }

        long pid = Long.parseLong(Files.readString(PID_FILE).trim());
        boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        assertFalse(alive, "child process from retired worker must not survive");
    }
}
