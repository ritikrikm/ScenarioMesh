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
    void implementedClasspathFeaturesAreRoutedToScenarioMeshOwner() {
        assertEquals(ExecutorConfigurationSemantics.Kind.REPLACED_BY_SCENARIOMESH,
                ExecutorConfigurationSemantics.forSurefire("additionalClasspathElements").kind());
        assertEquals(ExecutorConfigurationSemantics.Kind.REPLACED_BY_SCENARIOMESH,
                ExecutorConfigurationSemantics.forSurefire("classpathDependencyExcludes").kind());
        assertEquals(ExecutorConfigurationSemantics.Kind.REPLACED_BY_SCENARIOMESH,
                ExecutorConfigurationSemantics.forFailsafe("classpathDependencyScopeExclude").kind());
    }

    @Test
    void groupSelectionIsPreservedForProviderSpecificCompatibilityGating() {
        assertEquals(ExecutorConfigurationSemantics.Kind.PRESERVED,
                ExecutorConfigurationSemantics.forSurefire("groups").kind());
        assertEquals(ExecutorConfigurationSemantics.Kind.PRESERVED,
                ExecutorConfigurationSemantics.forFailsafe("excludedGroups").kind());
    }

    @Test
    void dependencyTestScanningIsPreservedForScenarioMeshOwnership() {
        var scan = ExecutorConfigurationSemantics.forSurefire("dependenciesToScan");
        assertEquals(ExecutorConfigurationSemantics.Kind.PRESERVED, scan.kind());
    }

    @Test
    void unknownFutureConfigurationFailsClosed() {
        assertEquals(ExecutorConfigurationSemantics.Kind.UNKNOWN,
                ExecutorConfigurationSemantics.forSurefire("futureOption").kind());
        assertEquals(ExecutorConfigurationSemantics.Kind.UNKNOWN,
                ExecutorConfigurationSemantics.forFailsafe("futureOption").kind());
    }
}
