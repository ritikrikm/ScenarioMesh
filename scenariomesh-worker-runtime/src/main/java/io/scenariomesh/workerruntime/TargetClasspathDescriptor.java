package io.scenariomesh.workerruntime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * JDK-only handoff for the target execution classpath.
 *
 * <p>The selected worker JVM must start from ScenarioMesh's control classpath only. Target test
 * classes, framework jars and adapter implementation jars are supplied separately through this
 * descriptor and loaded by {@link TargetRuntimeClassLoader}. Base64 keeps the descriptor robust
 * for spaces, separators and other ordinary path characters without depending on a JSON library.</p>
 */
public final class TargetClasspathDescriptor {
    private TargetClasspathDescriptor() {}

    public static void write(Path file, List<Path> classpath) throws Exception {
        if (file == null) throw new IllegalArgumentException("target classpath descriptor path is required");
        List<Path> normalized = normalize(classpath);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        List<String> lines = normalized.stream()
                .map(Path::toString)
                .map(value -> encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .toList();
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    public static List<Path> read(Path file) throws Exception {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("target classpath descriptor does not exist: " + file);
        }
        Base64.Decoder decoder = Base64.getUrlDecoder();
        List<Path> paths = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line == null || line.isBlank()) continue;
            try {
                String decoded = new String(decoder.decode(line.trim()), StandardCharsets.UTF_8);
                paths.add(Path.of(decoded).toAbsolutePath().normalize());
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("invalid target classpath descriptor entry at line " + lineNumber, invalid);
            }
        }
        if (paths.isEmpty()) throw new IllegalArgumentException("target classpath descriptor is empty: " + file);
        return List.copyOf(paths);
    }

    private static List<Path> normalize(List<Path> classpath) {
        if (classpath == null || classpath.isEmpty()) {
            throw new IllegalArgumentException("target execution classpath must not be empty");
        }
        List<Path> paths = new ArrayList<>();
        for (Path path : classpath) {
            if (path == null) continue;
            Path normalized = path.toAbsolutePath().normalize();
            if (!paths.contains(normalized)) paths.add(normalized);
        }
        if (paths.isEmpty()) throw new IllegalArgumentException("target execution classpath must not be empty");
        return List.copyOf(paths);
    }
}
