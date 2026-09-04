package io.scenariomesh.adapter.junitplatform;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JUnitPlatformExecutionContractsTest {

    @Test
    void adapterDeclarationAloneDoesNotProveAnUnknownEngine() {
        var decision = JUnitPlatformExecutionContracts.prove(evidence(
                "custom-engine", "example.CustomEngine", "1.0.0",
                JUnitPlatformExecutionContracts.DiscoveryShape.CLASS_SELECTION,
                Set.of(), Set.of(), true));

        assertFalse(decision.ownable());
        assertEquals("unproven", decision.profile());
    }

    @Test
    void provesCurrentJupiterMajorFamiliesAndRejectsAnUnprovenFutureMajor() {
        var junit5 = JUnitPlatformExecutionContracts.prove(evidence(
                "junit-jupiter", "org.junit.jupiter.engine.JupiterTestEngine", "5.14.4",
                JUnitPlatformExecutionContracts.DiscoveryShape.CLASS_SELECTION,
                Set.of(), Set.of(), true));
        var junit6 = JUnitPlatformExecutionContracts.prove(evidence(
                "junit-jupiter", "org.junit.jupiter.engine.JupiterTestEngine", "6.1.3",
                JUnitPlatformExecutionContracts.DiscoveryShape.CLASS_SELECTION,
                Set.of(), Set.of(), true));
        var junit7 = JUnitPlatformExecutionContracts.prove(evidence(
                "junit-jupiter", "org.junit.jupiter.engine.JupiterTestEngine", "7.0.0",
                JUnitPlatformExecutionContracts.DiscoveryShape.CLASS_SELECTION,
                Set.of(), Set.of(), true));

        assertTrue(junit5.ownable());
        assertTrue(junit6.ownable());
        assertFalse(junit7.ownable());
    }

    @Test
    void cucumberRequiresOfficialIdentitySupportedMajorAndRealDiscoveryEvidence() {
        var supported = JUnitPlatformExecutionContracts.prove(evidence(
                "cucumber", "io.cucumber.junit.platform.engine.CucumberTestEngine", "7.34.7",
                JUnitPlatformExecutionContracts.DiscoveryShape.CLASSPATH_RESOURCE,
                Set.of(), Set.of(), true));
        var spoofed = JUnitPlatformExecutionContracts.prove(evidence(
                "cucumber", "example.CucumberNamedEngine", "7.34.7",
                JUnitPlatformExecutionContracts.DiscoveryShape.CLASSPATH_RESOURCE,
                Set.of(), Set.of(), true));
        var futureMajor = JUnitPlatformExecutionContracts.prove(evidence(
                "cucumber", "io.cucumber.junit.platform.engine.CucumberTestEngine", "8.0.0",
                JUnitPlatformExecutionContracts.DiscoveryShape.CLASSPATH_RESOURCE,
                Set.of(), Set.of(), true));
        var fabricated = JUnitPlatformExecutionContracts.prove(evidence(
                "cucumber", "io.cucumber.junit.platform.engine.CucumberTestEngine", "7.34.7",
                JUnitPlatformExecutionContracts.DiscoveryShape.NONE,
                Set.of(), Set.of(), true));

        assertTrue(supported.ownable());
        assertEquals("cucumber-uniqueid-set-v1", supported.profile());
        assertFalse(spoofed.ownable());
        assertFalse(futureMajor.ownable());
        assertFalse(fabricated.ownable());
    }

    @Test
    void suiteOwnershipRequiresEveryNestedEngineToHaveItsOwnProvenIdentity() {
        var supported = JUnitPlatformExecutionContracts.prove(evidence(
                "junit-platform-suite", "org.junit.platform.suite.engine.SuiteTestEngine", "1.14.4",
                JUnitPlatformExecutionContracts.DiscoveryShape.CLASS_SELECTION,
                Set.of("junit-jupiter", "cucumber"), Set.of("junit-jupiter", "cucumber"), true));
        var unknownNested = JUnitPlatformExecutionContracts.prove(evidence(
                "junit-platform-suite", "org.junit.platform.suite.engine.SuiteTestEngine", "1.14.4",
                JUnitPlatformExecutionContracts.DiscoveryShape.CLASS_SELECTION,
                Set.of("junit-jupiter", "spock"), Set.of("junit-jupiter"), true));

        assertTrue(supported.ownable());
        assertEquals("platform-suite-scoped-v1", supported.profile());
        assertFalse(unknownNested.ownable());
        assertTrue(unknownNested.reason().contains("spock"));
    }

    @Test
    void suiteOwnershipRequiresClassDrivenSuiteDiscovery() {
        var decision = JUnitPlatformExecutionContracts.prove(evidence(
                "junit-platform-suite", "org.junit.platform.suite.engine.SuiteTestEngine", "6.1.3",
                JUnitPlatformExecutionContracts.DiscoveryShape.CLASSPATH_RESOURCE,
                Set.of("junit-jupiter"), Set.of("junit-jupiter"), true));

        assertFalse(decision.ownable());
    }

    @Test
    void adapterMustDeclareEvenAKnownEngine() {
        var decision = JUnitPlatformExecutionContracts.prove(evidence(
                "junit-jupiter", "org.junit.jupiter.engine.JupiterTestEngine", "5.10.5",
                JUnitPlatformExecutionContracts.DiscoveryShape.CLASS_SELECTION,
                Set.of(), Set.of(), false));

        assertFalse(decision.ownable());
    }

    private static JUnitPlatformExecutionContracts.Evidence evidence(
            String id,
            String implementation,
            String version,
            JUnitPlatformExecutionContracts.DiscoveryShape shape,
            Set<String> nested,
            Set<String> provenNested,
            boolean adapterDeclared) {
        return new JUnitPlatformExecutionContracts.Evidence(
                id, implementation, version, shape, nested, provenNested, adapterDeclared, 1);
    }
}
