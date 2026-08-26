package io.scenariomesh.maven.extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutorConfigurationSemanticsTest {

    @Test
    void concurrencyIsExplicitlyReplacedForBothExecutors() {
        assertEquals(ExecutorConfigurationSemantics.Kind.REPLACED_BY_SCENARIOMESH,
                ExecutorConfigurationSemantics.forSurefire("forkCount").kind());
        assertEquals(ExecutorConfigurationSemantics.Kind.REPLACED_BY_SCENARIOMESH,
                ExecutorConfigurationSemantics.forFailsafe("parallel").kind());
    }

    @Test
    void knownUnsupportedFeaturesNameTheirRequiredCapability() {
        var groups = ExecutorConfigurationSemantics.forFailsafe("groups");
        assertEquals(ExecutorConfigurationSemantics.Kind.REQUIRES_CAPABILITY, groups.kind());
        assertEquals("framework-group-selection", groups.capability());

        var classpath = ExecutorConfigurationSemantics.forSurefire("additionalClasspathDependencies");
        assertEquals(ExecutorConfigurationSemantics.Kind.REQUIRES_CAPABILITY, classpath.kind());
        assertEquals("executor-classpath-extension", classpath.capability());
    }

    @Test
    void unknownFutureConfigurationFailsClosed() {
        assertEquals(ExecutorConfigurationSemantics.Kind.UNKNOWN,
                ExecutorConfigurationSemantics.forSurefire("futureOption").kind());
        assertEquals(ExecutorConfigurationSemantics.Kind.UNKNOWN,
                ExecutorConfigurationSemantics.forFailsafe("futureOption").kind());
    }
}
