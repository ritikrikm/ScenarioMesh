package io.scenariomesh.maven.extension;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves Surefire/Failsafe additionalClasspathDependencies without project dependency management. */
final class MavenAdditionalClasspathDependencyResolver {
    private final RepositorySystem repositorySystem;

    MavenAdditionalClasspathDependencyResolver(RepositorySystem repositorySystem) {
        if (repositorySystem == null) throw new IllegalArgumentException("repositorySystem is required");
        this.repositorySystem = repositorySystem;
    }

    List<String> resolve(MavenSession session, MavenProject project, List<Dependency> dependencies) throws Exception {
        if (dependencies == null || dependencies.isEmpty()) return List.of();

        Set<String> reactorGavs = reactorGavs(session);
        rejectReactorRoots(reactorGavs, dependencies);
        Map<String, org.apache.maven.artifact.Artifact> firstByConflictId = new LinkedHashMap<>();
        for (Dependency dependency : dependencies) {
            Set<org.apache.maven.artifact.Artifact> resolved = resolveOne(session, project, dependency);
            for (org.apache.maven.artifact.Artifact artifact : resolved) {
                String gav = gav(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
                if (reactorGavs.contains(gav)) {
                    throw new IllegalStateException("additionalClasspathDependency resolved reactor artifact " + gav
                            + "; native Surefire supports only external dependencies for this parameter");
                }
                firstByConflictId.putIfAbsent(artifact.getDependencyConflictId(), artifact);
            }
        }

        List<String> paths = new ArrayList<>();
        for (org.apache.maven.artifact.Artifact artifact : firstByConflictId.values()) {
            File file = artifact.getFile();
            if (file == null) throw new IllegalStateException("resolved additional classpath artifact has no file: " + artifact);
            paths.add(file.getAbsoluteFile().toPath().normalize().toString());
        }
        return List.copyOf(paths);
    }

    static void rejectReactorRoots(Set<String> reactorGavs, List<Dependency> dependencies) {
        if (reactorGavs == null || reactorGavs.isEmpty() || dependencies == null) return;
        for (Dependency dependency : dependencies) {
            String dependencyGav = gav(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
            if (reactorGavs.contains(dependencyGav)) {
                throw new IllegalStateException("additionalClasspathDependency references reactor artifact " + dependencyGav
                        + "; native Surefire supports only external dependencies for this parameter");
            }
        }
    }

    private Set<org.apache.maven.artifact.Artifact> resolveOne(
            MavenSession session, MavenProject project, Dependency dependency) throws Exception {
        org.eclipse.aether.graph.Dependency aetherDependency = RepositoryUtils.toDependency(
                dependency, session.getRepositorySession().getArtifactTypeRegistry());

        // Surefire intentionally collects without a synthetic root so optional transitive
        // dependencies stay omitted exactly as Maven Resolver would for a normal consumer.
        CollectRequest collect = new CollectRequest(
                Collections.singletonList(aetherDependency), null, project.getRemoteProjectRepositories());
        DependencyFilter runtimeClasspath = DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME);
        DependencyRequest request = new DependencyRequest(collect, runtimeClasspath);
        DependencyResult result = repositorySystem.resolveDependencies(session.getRepositorySession(), request);

        LinkedHashSet<org.apache.maven.artifact.Artifact> artifacts = new LinkedHashSet<>();
        for (ArtifactResult artifactResult : result.getArtifactResults()) {
            artifacts.add(RepositoryUtils.toArtifact(artifactResult.getArtifact()));
        }
        return artifacts;
    }

    private Set<String> reactorGavs(MavenSession session) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (session.getProjects() == null) return values;
        for (MavenProject project : session.getProjects()) {
            values.add(gav(project.getGroupId(), project.getArtifactId(), project.getVersion()));
        }
        return values;
    }

    private static String gav(String groupId, String artifactId, String version) {
        return groupId + ":" + artifactId + ":" + version;
    }
}
