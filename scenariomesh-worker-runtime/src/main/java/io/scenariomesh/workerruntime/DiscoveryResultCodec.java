package io.scenariomesh.workerruntime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * JDK-only codec for the private discovery subprocess handoff.
 *
 * <p>This is intentionally not the public worker protocol. Discovery runs on the
 * target test classpath, so using target-visible JSON libraries here would let a
 * repository's dependency versions influence ScenarioMesh control-plane behavior.</p>
 */
public final class DiscoveryResultCodec {
    private static final int FORMAT_VERSION = 1;

    private DiscoveryResultCodec() {}

    public static void write(Path output, DiscoveryMain.DiscoveryResult result) throws Exception {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(result, "result");
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (ObjectOutputStream stream = new ObjectOutputStream(
                new BufferedOutputStream(Files.newOutputStream(output)))) {
            stream.writeInt(FORMAT_VERSION);
            stream.writeObject(result);
        }
    }

    public static DiscoveryMain.DiscoveryResult read(Path input) throws Exception {
        Objects.requireNonNull(input, "input");
        try (ObjectInputStream stream = new ObjectInputStream(
                new BufferedInputStream(Files.newInputStream(input)))) {
            int version = stream.readInt();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException("Unsupported ScenarioMesh discovery result format " + version
                        + "; expected " + FORMAT_VERSION);
            }
            Object value = stream.readObject();
            if (!(value instanceof DiscoveryMain.DiscoveryResult result)) {
                throw new IllegalStateException("ScenarioMesh discovery result contained unexpected type "
                        + (value == null ? "null" : value.getClass().getName()));
            }
            return result;
        }
    }
}
