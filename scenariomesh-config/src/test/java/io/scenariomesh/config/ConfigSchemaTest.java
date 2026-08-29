package io.scenariomesh.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSchemaTest {
    @Test
    void yamlSchemaIsDerivedFromCanonicalConfigKeys() {
        Set<String> paths = ConfigKey.yamlPaths();
        assertTrue(paths.contains("enabled"));
        assertTrue(paths.contains("execution.adapter"));
        assertTrue(paths.contains("distributed.tls.trustStorePassword"));
        assertTrue(paths.contains("logging.showProgress"));
        assertFalse(paths.contains("config.file"), "config-file location is a bootstrap option, not a YAML value");
    }

    @Test
    void everyYamlVisibleKeyHasCanonicalNamespacedProperty() {
        for (ConfigKey key : ConfigKey.values()) {
            assertTrue(key.canonical().startsWith("scenariomesh."), key.name());
            key.yamlPath().ifPresent(path -> assertFalse(path.startsWith("scenariomesh."), key.name()));
        }
    }
}
