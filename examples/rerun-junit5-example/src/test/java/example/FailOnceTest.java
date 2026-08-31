package example;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.fail;

class FailOnceTest {
    @Test
    void passesOnMavenRerun() throws Exception {
        Path marker = Path.of("target", "rerun-fixture", "first-attempt-seen");
        Files.createDirectories(marker.getParent());
        if (Files.notExists(marker)) {
            Files.writeString(marker, "failed-once");
            fail("deterministic first-attempt failure");
        }
    }
}
