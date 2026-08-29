package io.scenariomesh.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenOwnershipDiagnosticTest {
    @Test
    void emitsStableOwnershipFieldsWithoutMultilineOutput() {
        String diagnostic = MavenOwnershipDiagnostic.format(
                MavenOwnershipDiagnostic.Owner.PASS_THROUGH,
                "checkout-tests",
                "surefire",
                "default-test",
                "method selector is unsupported\nretain native Maven");

        assertTrue(diagnostic.startsWith("MAVEN_OWNERSHIP owner=PASS_THROUGH"));
        assertTrue(diagnostic.contains("module=checkout-tests"));
        assertTrue(diagnostic.contains("executor=surefire"));
        assertTrue(diagnostic.contains("execution=default-test"));
        assertFalse(diagnostic.contains("\n"));
        assertFalse(diagnostic.contains("SUREFIRE_CAPSULE"));
    }

    @Test
    void frameworkCapsuleIsReservedAsARealOwnerKind() {
        String diagnostic = MavenOwnershipDiagnostic.format(
                MavenOwnershipDiagnostic.Owner.FRAMEWORK_CAPSULE,
                "module",
                "none",
                "none",
                "framework-native owner");
        assertTrue(diagnostic.contains("owner=FRAMEWORK_CAPSULE"));
    }
}
