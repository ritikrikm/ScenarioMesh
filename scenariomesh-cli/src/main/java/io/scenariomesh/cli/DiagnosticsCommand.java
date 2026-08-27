package io.scenariomesh.cli;

import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.config.ConfigResolver;
import io.scenariomesh.protocol.Protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a bounded, allowlist-only diagnostics archive without collecting raw environment or worker logs. */
final class DiagnosticsCommand {
    private static final long MAX_ENTRY_BYTES = 20L * 1024L * 1024L;
    private static final String[] REPORT_ALLOWLIST = {"summary.json", "junit.xml", "report.html"};

    int run(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            Path root = parsed.root().toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                System.err.println("ScenarioMesh diagnostics root does not exist: " + root);
                return 2;
            }
            Path buildDirectory = root.resolve("target");
            ScenarioMeshConfig config = new ConfigResolver().resolve(
                    root, buildDirectory, systemProperties(), System.getenv());
            Path output = parsed.output() == null
                    ? buildDirectory.resolve("scenariomesh-diagnostics.zip")
                    : resolve(root, parsed.output());
            Files.createDirectories(output.toAbsolutePath().normalize().getParent());
            writeBundle(root, config, output.toAbsolutePath().normalize());
            System.out.println("ScenarioMesh diagnostics: " + output.toAbsolutePath().normalize());
            return 0;
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return 2;
        } catch (Exception exception) {
            System.err.println("Unable to create ScenarioMesh diagnostics: " + safeMessage(exception));
            return 1;
        }
    }

    private void writeBundle(Path root, ScenarioMeshConfig config, Path output) throws IOException {
        Path reporting = config.reportingDirectory().toAbsolutePath().normalize();
        try (OutputStream stream = Files.newOutputStream(output);
             ZipOutputStream zip = new ZipOutputStream(stream, StandardCharsets.UTF_8)) {
            writeText(zip, "diagnostics/manifest.json", manifest(root, config));
            for (String name : REPORT_ALLOWLIST) addIfSafe(zip, reporting.resolve(name), "reports/" + name);
            Path latestRun = latestRun(reporting.resolve("runs"));
            if (latestRun != null) {
                addIfSafe(zip, latestRun.resolve("events.jsonl"), "run/events.jsonl");
            }
        }
    }

    private String manifest(Path root, ScenarioMeshConfig config) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("scenarioMeshVersion", ScenarioMeshVersion.current());
        values.put("protocolVersion", Integer.toString(Protocol.VERSION));
        values.put("javaVersion", System.getProperty("java.version", "unknown"));
        values.put("javaVendor", System.getProperty("java.vendor", "unknown"));
        values.put("osName", System.getProperty("os.name", "unknown"));
        values.put("osArch", System.getProperty("os.arch", "unknown"));
        values.put("executionAdapter", config.executionAdapter());
        values.put("schedulingStrategy", config.schedulingMode().externalValue());
        values.put("workerMode", config.distributed().mode().externalValue());
        values.put("workerCount", Integer.toString(config.workerCount()));
        values.put("minimumReadyWorkers", Integer.toString(config.minimumReadyWorkers()));
        values.put("tlsEnabled", Boolean.toString(config.distributed().tls().enabled()));
        values.put("tlsClientAuthRequired", Boolean.toString(config.distributed().tls().requireClientAuth()));
        values.put("reportingDirectory", displayPath(root, config.reportingDirectory()));
        StringBuilder json = new StringBuilder("{\n");
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (index++ > 0) json.append(",\n");
            json.append("  \"").append(escape(entry.getKey())).append("\": \"")
                    .append(escape(entry.getValue())).append("\"");
        }
        return json.append("\n}\n").toString();
    }

    private void addIfSafe(ZipOutputStream zip, Path source, String entryName) throws IOException {
        if (!Files.isRegularFile(source)) return;
        long size = Files.size(source);
        if (size < 0 || size > MAX_ENTRY_BYTES) return;
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        Files.copy(source, zip);
        zip.closeEntry();
    }

    private void writeText(ZipOutputStream zip, String entryName, String text) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private Path latestRun(Path runs) throws IOException {
        if (!Files.isDirectory(runs)) return null;
        try (var stream = Files.list(runs)) {
            return stream.filter(Files::isDirectory)
                    .max(Comparator.comparing(this::modifiedTime))
                    .orElse(null);
        }
    }

    private FileTime modifiedTime(Path path) {
        try { return Files.getLastModifiedTime(path); }
        catch (IOException ignored) { return FileTime.fromMillis(0L); }
    }

    private static Map<String, String> systemProperties() {
        Properties properties = System.getProperties();
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) values.put(name, properties.getProperty(name));
        return Map.copyOf(values);
    }

    private static Path resolve(Path root, Path path) {
        return (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
    }

    private static String displayPath(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            if (normalized.startsWith(root)) return root.relativize(normalized).toString();
        } catch (Exception ignored) { }
        return normalized.toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record Arguments(Path root, Path output) {
        static Arguments parse(String[] args) {
            Path root = Path.of(".");
            Path output = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--root" -> {
                        if (++i >= args.length) throw new IllegalArgumentException("--root requires a path");
                        root = Path.of(args[i]);
                    }
                    case "--output" -> {
                        if (++i >= args.length) throw new IllegalArgumentException("--output requires a path");
                        output = Path.of(args[i]);
                    }
                    default -> throw new IllegalArgumentException("Unknown diagnostics option: " + args[i]);
                }
            }
            return new Arguments(root, output);
        }
    }
}
