package io.scenariomesh.maven;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MavenTargetJUnitPlatformClasspathTest {
    @Test
    void suppliesEngineAlignedLauncherWhenTargetHasNoLauncherDependency() {
        assertEquals("1.11.2", MavenTargetJUnitPlatformClasspath.targetLauncherVersion("1.11.2", Set.of()));
    }

    @Test
    void preservesTargetLauncherWhenMavenResolvedExactSameVersion() {
        assertEquals("1.11.4", MavenTargetJUnitPlatformClasspath.targetLauncherVersion(
                "1.11.4", Set.of("1.11.4")));
    }

    @Test
    void preservesTargetsResolvedMixedPatchGraphInsteadOfRewritingIt() {
        assertEquals("1.11.4", MavenTargetJUnitPlatformClasspath.targetLauncherVersion(
                "1.11.2", Set.of("1.11.4")));
    }

    @Test
    void failsClosedWhenMoreThanOneLauncherVersionSurvivesResolution() {
        Set<String> launchers = new LinkedHashSet<>();
        launchers.add("1.11.2");
        launchers.add("1.11.4");

        assertThrows(IllegalStateException.class, () ->
                MavenTargetJUnitPlatformClasspath.targetLauncherVersion("1.11.2", launchers));
    }

    @Test
    void rejectsMissingEngineVersionEvidence() {
        assertThrows(IllegalArgumentException.class, () ->
                MavenTargetJUnitPlatformClasspath.targetLauncherVersion(" ", Set.of("1.11.4")));
    }
}
