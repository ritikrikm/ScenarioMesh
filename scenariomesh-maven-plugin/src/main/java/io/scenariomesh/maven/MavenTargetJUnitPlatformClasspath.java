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

/** Builds the adapter runtime from the JUnit Platform graph Maven resolved for the target. */
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
        String engineVersion = engineVersions.iterator().next();
        Set<String> launcherVersions = versions(project, PLATFORM_LAUNCHER);
        String launcherVersion = targetLauncherVersion(engineVersion, launcherVersions);

        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(new DefaultArtifact(
                PLATFORM_GROUP, PLATFORM_LAUNCHER, "jar", launcherVersion));
        request.setRepositories(project.getRemoteProjectRepositories());
        ArtifactResult result = repositorySystem.resolveArtifact(session.getRepositorySession(), request);
        File file = result.getArtifact() == null ? null : result.getArtifact().getFile();
        if (file == null || !file.isFile()) {
            throw new IllegalStateException("target JUnit Platform launcher " + launcherVersion + " has no resolved file");
        }
        return List.of(file.getAbsoluteFile().toPath().normalize().toString());
    }

    /**
     * Preserve Maven's resolved launcher when the project already has one. The target realm is the
     * semantic authority: ScenarioMesh must execute against the same mixed-but-resolved Platform
     * graph that native Surefire/Failsafe would see rather than rewriting it to artificial exact
     * version equality. Runtime preflight still has to load that graph and prove discovery and
     * execution capabilities before ownership is granted.
     *
     * <p>If the project has no launcher dependency, ScenarioMesh supplies the launcher that matches
     * the single resolved Platform engine API. This covers projects where the native Maven provider
     * normally contributes the launcher outside the project test realm.</p>
     */
    static String targetLauncherVersion(String engineVersion, Set<String> launcherVersions) {
        if (engineVersion == null || engineVersion.isBlank()) {
            throw new IllegalArgumentException("engineVersion is required");
        }
        if (launcherVersions == null || launcherVersions.isEmpty()) return engineVersion;
        if (launcherVersions.size() != 1) {
            throw new IllegalStateException("Maven resolved multiple JUnit Platform launcher versions " + launcherVersions);
        }
        return launcherVersions.iterator().next();
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
