package example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class ContractRecorder {
    private ContractRecorder() {}

    static synchronized void record(String event) {
        try {
            Path trace = Path.of(System.getProperty("contract.trace", "target/maven-equivalence-events.log"));
            Files.createDirectories(trace.getParent());
            String line = String.join("|",
                    event,
                    "property=" + System.getProperty("contract.pom", "<missing>"),
                    "precedence=" + System.getProperty("contract.precedence", "<missing>"),
                    "env=" + String.valueOf(System.getenv("CONTRACT_ENV")),
                    "configured=" + String.valueOf(System.getenv("CONTRACT_CONFIGURED")),
                    "excluded=" + String.valueOf(System.getenv("CONTRACT_EXCLUDED")),
                    "overlay=" + String.valueOf(System.getenv("CONTRACT_OVERLAY")),
                    "empty=" + String.valueOf(System.getenv("CONTRACT_EMPTY")),
                    "assertions=" + AlphaContractTest.class.desiredAssertionStatus(),
                    "cwd=" + Path.of("").toAbsolutePath().normalize()) + System.lineSeparator();
            Files.writeString(trace, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot record Maven equivalence event", exception);
        }
    }
}
