package io.scenariomesh.adapter.junitplatform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JUnitPlatformEngineVersionTest {

    @Test
    void acceptsReleaseAndQualifiedSemanticVersions() {
        assertTrue(JUnitPlatformEngineVersion.isSemanticVersion("7.34.7"));
        assertTrue(JUnitPlatformEngineVersion.isSemanticVersion("6.1.3"));
        assertTrue(JUnitPlatformEngineVersion.isSemanticVersion("7.35.0-SNAPSHOT"));
        assertTrue(JUnitPlatformEngineVersion.isSemanticVersion("6.2.0-RC1"));
    }

    @Test
    void rejectsPlaceholderOrUnverifiableVersions() {
        assertFalse(JUnitPlatformEngineVersion.isSemanticVersion("DEVELOPMENT"));
        assertFalse(JUnitPlatformEngineVersion.isSemanticVersion("unknown"));
        assertFalse(JUnitPlatformEngineVersion.isSemanticVersion(""));
        assertFalse(JUnitPlatformEngineVersion.isSemanticVersion(null));
        assertFalse(JUnitPlatformEngineVersion.isSemanticVersion("v7.34.7"));
    }
}
