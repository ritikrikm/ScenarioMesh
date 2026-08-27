package io.scenariomesh.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsCommandTest {
    @TempDir
    Path root;

    @Test
    void createsAllowlistOnlyBundleWithoutConfiguredSecretsOrRawWorkerLogs() throws Exception {
        String secret = "diagnostics-super-secret-token";
        Files.writeString(root.resolve("scenariomesh.yml"), """
                scenariomesh:
                  configVersion: 1
                  workers:
                    mode: remote
                  distributed:
                    bindHost: 127.0.0.1
                    bindPort: 43123
                    token: %s
                  scheduling:
                    strategy: fifo
                """.formatted(secret));
        Path reporting = root.resolve("target/scenariomesh");
        Path run = reporting.resolve("runs/run-1");
        Files.createDirectories(run.resolve("logs"));
        Files.writeString(reporting.resolve("summary.json"), "{\"passed\":1}\n");
        Files.writeString(reporting.resolve("junit.xml"), "<testsuite tests=\"1\"/>\n");
        Files.writeString(reporting.resolve("report.html"), "<html>ok</html>\n");
        Files.writeString(run.resolve("events.jsonl"), "{\"type\":\"RUN\"}\n");
        Files.writeString(run.resolve("logs/worker-1.log"), "RAW-SECRET-SHOULD-NOT-BE-BUNDLED=" + secret);

        assertEquals(0, new DiagnosticsCommand().run(new String[]{"--root", root.toString()}));

        Path archive = root.resolve("target/scenariomesh-diagnostics.zip");
        assertTrue(Files.isRegularFile(archive));
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Set<String> entries = new HashSet<>();
            zip.stream().map(ZipEntry::getName).forEach(entries::add);
            assertEquals(Set.of("diagnostics/manifest.json", "reports/summary.json", "reports/junit.xml",
                    "reports/report.html", "run/events.jsonl"), entries);
            String manifest = new String(zip.getInputStream(zip.getEntry("diagnostics/manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(manifest.contains("\"protocolVersion\""));
            assertTrue(manifest.contains("\"schedulingStrategy\": \"fifo\""));
            assertFalse(manifest.contains(secret));
            assertFalse(manifest.toLowerCase().contains("token"));
        }
    }
}
