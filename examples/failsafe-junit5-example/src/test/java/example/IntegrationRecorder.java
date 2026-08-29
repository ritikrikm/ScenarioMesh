package example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class IntegrationRecorder {
    private IntegrationRecorder() {}

    static synchronized void record(String event) {
        try {
            Path trace = Path.of(System.getProperty("contract.trace", "target/maven-equivalence-events.log"));
            Files.createDirectories(trace.toAbsolutePath().getParent());
            Files.writeString(trace, event + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot record Failsafe equivalence event", exception);
        }
    }
}
