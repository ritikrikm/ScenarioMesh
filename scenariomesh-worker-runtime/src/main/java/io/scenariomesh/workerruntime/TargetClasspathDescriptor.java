package io.scenariomesh.workerruntime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** JDK-only handoff for the target execution classpath. */
public final class TargetClasspathDescriptor {
    public static final String SYSTEM_PROPERTY = "scenariomesh.internal.targetClasspath";
    private static final String INLINE_SEPARATOR = ".";

    private TargetClasspathDescriptor() {}

    public static void write(Path file, List<Path> classpath) throws Exception {
        if (file == null) throw new IllegalArgumentException("target classpath descriptor path is required");
        List<Path> normalized = normalize(classpath);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        List<String> lines = normalized.stream().map(Path::toString)
                .map(value -> encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))).toList();
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    public static List<Path> read(Path file) throws Exception {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("target classpath descriptor does not exist: " + file);
        }
        return decodeEntries(Files.readAllLines(file, StandardCharsets.UTF_8), "descriptor " + file);
    }

    /** Encodes a classpath into one internal JVM-property value. */
    public static String encodeInline(List<Path> classpath) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return normalize(classpath).stream().map(Path::toString)
                .map(value -> encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .collect(java.util.stream.Collectors.joining(INLINE_SEPARATOR));
    }

    public static List<Path> decodeInline(String encoded) {
        if (encoded == null || encoded.isBlank()) throw new IllegalArgumentException("encoded target classpath is empty");
        return decodeEntries(List.of(encoded.split(java.util.regex.Pattern.quote(INLINE_SEPARATOR), -1)), "inline target classpath");
    }

    private static List<Path> decodeEntries(List<String> entries, String source) {
        Base64.Decoder decoder = Base64.getUrlDecoder();
        List<Path> paths = new ArrayList<>();
        int index = 0;
        for (String entry : entries) {
            index++;
            if (entry == null || entry.isBlank()) continue;
            try {
                String decoded = new String(decoder.decode(entry.trim()), StandardCharsets.UTF_8);
                Path path = Path.of(decoded).toAbsolutePath().normalize();
                if (!paths.contains(path)) paths.add(path);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("invalid " + source + " entry " + index, invalid);
            }
        }
        if (paths.isEmpty()) throw new IllegalArgumentException(source + " is empty");
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
