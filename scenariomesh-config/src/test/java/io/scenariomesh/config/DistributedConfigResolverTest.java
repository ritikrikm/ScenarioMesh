package io.scenariomesh.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DistributedConfigResolverTest {
    @TempDir
    Path project;

    @Test
    void remoteModeResolvesFromCanonicalPropertiesOnLoopbackWithoutTls() {
        ScenarioMeshConfig config = new ConfigResolver().resolve(project, project.resolve("target"), Map.of(
                "scenariomesh.workers.mode", "remote",
                "scenariomesh.distributed.bindHost", "127.0.0.1",
                "scenariomesh.distributed.bindPort", "42117",
                "scenariomesh.distributed.token", "secret-value",
                "scenariomesh.distributed.registrationTimeout", "PT45S"), Map.of());

        assertEquals(DistributedConfig.WorkerMode.REMOTE, config.distributed().mode());
        assertEquals("127.0.0.1", config.distributed().bindHost());
        assertEquals(42117, config.distributed().bindPort());
        assertEquals("secret-value", config.distributed().token());
        assertEquals(45, config.distributed().registrationTimeout().toSeconds());
        assertFalse(config.distributed().tls().enabled());
    }

    @Test
    void explicitPropertyOverridesEnvironmentAndYaml() throws Exception {
        Files.writeString(project.resolve("scenariomesh.yml"), """
                scenariomesh:
                  configVersion: 1
                  workers:
                    mode: remote
                  distributed:
                    bindPort: 41000
                    token: yaml-token
                """);
        ScenarioMeshConfig config = new ConfigResolver().resolve(project, project.resolve("target"), Map.of(
                "scenariomesh.workers.mode", "remote",
                "scenariomesh.distributed.bindPort", "43000",
                "scenariomesh.distributed.token", "property-token"), Map.of(
                "SCENARIOMESH_DISTRIBUTED_BIND_PORT", "42000",
                "SCENARIOMESH_DISTRIBUTED_TOKEN", "env-token"));
        assertEquals(43000, config.distributed().bindPort());
        assertEquals("property-token", config.distributed().token());
    }

    @Test
    void remoteModeFailsClosedWithoutExplicitPortAndToken() {
        assertThrows(IllegalArgumentException.class, () -> new ConfigResolver().resolve(
                project, project.resolve("target"), Map.of("scenariomesh.workers.mode", "remote"), Map.of()));
    }
}
