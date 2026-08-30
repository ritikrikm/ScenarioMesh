package io.scenariomesh.maven.extension;

import org.apache.maven.model.Dependency;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenAdditionalClasspathDependencyResolverTest {
    @Test
    void rejectsAdditionalRootThatTargetsCurrentReactor() {
        Dependency dependency = dependency("example", "shared-test-support", "1.0");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> MavenAdditionalClasspathDependencyResolver.rejectReactorRoots(
                        Set.of("example:shared-test-support:1.0"), List.of(dependency)));

        assertTrue(exception.getMessage().contains("reactor artifact"), exception.getMessage());
    }

    @Test
    void externalAdditionalRootRemainsEligibleForResolver() {
        assertDoesNotThrow(() -> MavenAdditionalClasspathDependencyResolver.rejectReactorRoots(
                Set.of("example:app:1.0"),
                List.of(dependency("org.example", "external-support", "2.0"))));
    }

    private Dependency dependency(String groupId, String artifactId, String version) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion(version);
        return dependency;
    }
}
