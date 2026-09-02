package io.scenariomesh.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds the adapter's runtime against the JUnit Platform version Maven resolved for the target. */
final class MavenTargetJUnitPlatformClasspath {
    private static final String PLATFORM_GROUP = "org.junit.platform";
    private static final String PLATFORM_ENGINE = "junit-platform-engine";
    private static final String PLATFORM_LAUNCHER = "junit-platform-launcher";

    private final RepositorySystem repositorySystem;

    MavenTargetJUnitPlatformClasspath(RepositorySystem repositorySystem) {
        if (repositorySystem == null) throw new IllegalArgumentException("repositorySystem is required");
        this.repositorySystem = repositorySystem;
    }

    List<String> resolve(MavenProject project, MavenSession session) throws Exception {
        Set<String> engineVersions = versions(project, PLATFORM_ENGINE);
        if (engineVersions.isEmpty()) return List.of();
        if (engineVersions.size() != 1) {
            throw new IllegalStateException("Maven resolved multiple JUnit Platform engine versions " + engineVersions);
        }
        String version = engineVersions.iterator().next();
        Set<String> launcherVersions = versions(project, PLATFORM_LAUNCHER);
        if (!launcherVersions.isEmpty() && !launcherVersions.equals(Set.of(version))) {
            throw new IllegalStateException("target JUnit Platform engine " + version
                    + " is not aligned with launcher " + launcherVersions);
        }

        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(new DefaultArtifact(
                PLATFORM_GROUP, PLATFORM_LAUNCHER, "jar", version));
        request.setRepositories(project.getRemoteProjectRepositories());
        ArtifactResult result = repositorySystem.resolveArtifact(session.getRepositorySession(), request);
        File file = result.getArtifact() == null ? null : result.getArtifact().getFile();
        if (file == null || !file.isFile()) {
            throw new IllegalStateException("aligned JUnit Platform launcher " + version + " has no resolved file");
        }
        return List.of(file.getAbsoluteFile().toPath().normalize().toString());
    }

    private Set<String> versions(MavenProject project, String artifactId) {
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        if (project.getArtifacts() == null) return versions;
        for (Artifact artifact : project.getArtifacts()) {
            if (PLATFORM_GROUP.equals(artifact.getGroupId()) && artifactId.equals(artifact.getArtifactId())) {
                versions.add(artifact.getVersion());
            }
        }
        return versions;
    }
}
