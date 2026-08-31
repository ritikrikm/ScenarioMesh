package io.scenariomesh.maven;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ZeroTestPolicyTest {
    @Test
    void defaultSurefireEmptyTestSetDoesNotFail() {
        assertNull(ZeroTestPolicy.failureMessage(0, true, false, false, true));
    }

    @Test
    void failIfNoTestsFailsAnUnspecifiedEmptyTestSet() {
        assertEquals("No tests were executed.",
                ZeroTestPolicy.failureMessage(0, true, false, true, true));
    }

    @Test
    void explicitSelectorUsesFailIfNoSpecifiedTestsPolicy() {
        assertEquals("No tests matching the requested Surefire -Dtest selection were executed.",
                ZeroTestPolicy.failureMessage(0, true, true, false, true));
    }

    @Test
    void disablingFailIfNoSpecifiedTestsAllowsAnExplicitSelectorToMatchNothing() {
        assertNull(ZeroTestPolicy.failureMessage(0, true, true, false, false));
    }

    @Test
    void discoveredTestsAlwaysBypassZeroTestFailurePolicy() {
        assertNull(ZeroTestPolicy.failureMessage(1, true, true, true, true));
    }

    @Test
    void directScenarioMeshRunsRemainUnaffectedWhenMavenPolicyIsNotEnabled() {
        assertNull(ZeroTestPolicy.failureMessage(0, false, true, true, true));
    }
}
