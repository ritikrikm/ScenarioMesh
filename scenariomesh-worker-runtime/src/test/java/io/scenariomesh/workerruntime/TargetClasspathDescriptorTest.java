package io.scenariomesh.workerruntime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TargetClasspathDescriptorTest {
    @Test
    void inlineEncodingRoundTripsPlatformSensitivePaths() {
        List<Path> input = List.of(
                Path.of("target", "classes with spaces").toAbsolutePath().normalize(),
                Path.of("target", "semicolon;comma,equals=jar.jar").toAbsolutePath().normalize());
        assertEquals(input, TargetClasspathDescriptor.decodeInline(TargetClasspathDescriptor.encodeInline(input)));
    }

    @Test
    void fileEncodingRoundTrips() throws Exception {
        List<Path> input = List.of(Path.of("target", "test-classes").toAbsolutePath().normalize());
        Path file = Files.createTempFile("scenariomesh-target-classpath", ".txt");
        TargetClasspathDescriptor.write(file, input);
        assertEquals(input, TargetClasspathDescriptor.read(file));
    }
}
