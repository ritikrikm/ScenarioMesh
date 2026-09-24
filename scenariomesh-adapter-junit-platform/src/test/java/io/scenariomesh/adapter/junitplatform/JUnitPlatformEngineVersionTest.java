package io.scenariomesh.adapter.junitplatform;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void toleratesLinkageFailureFromEngineReportedVersion() {
        TestEngine engine = new TestEngine() {
            @Override
            public String getId() {
                return "linkage-fixture";
            }

            @Override
            public Optional<String> getVersion() {
                throw new NoSuchMethodError("mixed JUnit Platform graph");
            }

            @Override
            public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId uniqueId) {
                return new EngineDescriptor(uniqueId, "linkage fixture");
            }

            @Override
            public void execute(ExecutionRequest request) {
                // Not needed for version evidence.
            }
        };

        JUnitPlatformEngineVersion.VersionEvidence evidence =
                assertDoesNotThrow(() -> JUnitPlatformEngineVersion.resolve(engine));

        assertEquals("unknown", evidence.reportedVersion());
    }
}
