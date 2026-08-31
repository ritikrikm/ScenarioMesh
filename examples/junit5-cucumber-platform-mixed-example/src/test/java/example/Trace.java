package example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class Trace {
    private Trace() {}

    public static synchronized void record(String event) throws Exception {
        Path trace = Path.of(System.getProperty("contract.trace"));
        Files.createDirectories(trace.toAbsolutePath().getParent());
        Files.writeString(trace, event + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
