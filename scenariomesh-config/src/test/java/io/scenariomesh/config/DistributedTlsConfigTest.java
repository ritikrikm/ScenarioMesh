package io.scenariomesh.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributedTlsConfigTest {
    @TempDir Path project;
    private final ConfigResolver resolver = new ConfigResolver();

    @Test
    void rejectsNonLoopbackPlaintextRemoteCoordinator() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                project, project.resolve("target"), Map.of(
                        "scenariomesh.workers.mode", "remote",
                        "scenariomesh.distributed.bindHost", "0.0.0.0",
                        "scenariomesh.distributed.bindPort", "4444",
                        "scenariomesh.distributed.token", "secret"), Map.of()));
        assertTrue(error.getMessage().contains("requires distributed.tls.enabled=true"));
    }

    @Test
    void resolvesTlsStoresFromEnvironmentWithoutLoggingOrGuessingPaths() throws Exception {
        Path key = Files.createFile(project.resolve("worker.p12"));
        Path trust = Files.createFile(project.resolve("trust.p12"));
        ScenarioMeshConfig config = resolver.resolve(project, project.resolve("target"), Map.of(), Map.of(
                "SCENARIOMESH_WORKERS_MODE", "remote",
                "SCENARIOMESH_DISTRIBUTED_BIND_HOST", "0.0.0.0",
                "SCENARIOMESH_DISTRIBUTED_BIND_PORT", "4444",
                "SCENARIOMESH_DISTRIBUTED_TOKEN", "secret",
                "SCENARIOMESH_DISTRIBUTED_TLS_ENABLED", "true",
                "SCENARIOMESH_DISTRIBUTED_TLS_KEY_STORE", key.toString(),
                "SCENARIOMESH_DISTRIBUTED_TLS_KEY_STORE_PASSWORD", "changeit",
                "SCENARIOMESH_DISTRIBUTED_TLS_TRUST_STORE", trust.toString(),
                "SCENARIOMESH_DISTRIBUTED_TLS_TRUST_STORE_PASSWORD", "changeit"));
        assertTrue(config.distributed().tls().enabled());
        assertTrue(config.distributed().tls().requireClientAuth());
        assertEquals(key.toAbsolutePath().normalize(), config.distributed().tls().keyStore());
        assertEquals(trust.toAbsolutePath().normalize(), config.distributed().tls().trustStore());
    }

    @Test
    void configDiagnosticsNeverExposeAuthenticationSecrets() {
        TlsConfig tls = new TlsConfig(true, true,
                Path.of("worker.p12"), "key-password",
                Path.of("trust.p12"), "trust-password");
        DistributedConfig distributed = new DistributedConfig(
                DistributedConfig.WorkerMode.REMOTE,
                "127.0.0.1", 4444, "remote-token", Duration.ofSeconds(10), tls);

        String text = distributed.toString();
        assertFalse(text.contains("remote-token"));
        assertFalse(text.contains("key-password"));
        assertFalse(text.contains("trust-password"));
        assertTrue(text.contains("<redacted>"));
    }
}
