package example;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/** One deliberately uncertain lifecycle scope. ScenarioMesh must not rerun it. */
class WorkerCrashRecoveryTest {
    @Test
    void a_crashWorker() throws Exception {
        Path marker = Path.of("target", "crash-once.marker");
        Files.createDirectories(marker.getParent());
        if (Files.notExists(marker)) {
            Files.writeString(marker, "crashed");
            Runtime.getRuntime().halt(23);
        }
    }
}
