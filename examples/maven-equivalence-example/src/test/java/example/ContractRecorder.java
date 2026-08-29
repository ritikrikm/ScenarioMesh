package example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class ContractRecorder {
    private static final Path TRACE = Path.of("target", "maven-equivalence-events.log");

    private ContractRecorder() {}

    static synchronized void record(String event) {
        try {
            Files.createDirectories(TRACE.getParent());
            String line = String.join("|",
                    event,
                    "property=" + System.getProperty("contract.pom", "<missing>"),
                    "env=" + String.valueOf(System.getenv("CONTRACT_ENV")),
                    "cwd=" + Path.of("").toAbsolutePath().normalize()) + System.lineSeparator();
            Files.writeString(TRACE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot record Maven equivalence event", exception);
        }
    }
}
