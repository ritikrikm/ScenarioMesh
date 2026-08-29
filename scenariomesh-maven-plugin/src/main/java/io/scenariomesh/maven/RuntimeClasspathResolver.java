package io.scenariomesh.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.artifact.filter.PatternIncludesArtifactFilter;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shared runtime/test classpath assembly used by preflight and execution. */
final class RuntimeClasspathResolver {
    RuntimeClasspaths resolveSplit(MavenProject project, List<Artifact> pluginArtifacts) throws Exception {
        return resolveSplit(project, pluginArtifacts, List.of(), List.of(), null);
    }

    RuntimeClasspaths resolveSplit(MavenProject project,
                                   List<Artifact> pluginArtifacts,
                                   List<String> additionalClasspathElements,
                                   List<String> classpathDependencyExcludes,
                                   String classpathDependencyScopeExclude) throws Exception {
        Set<Path> plugin = pluginClasspath(pluginArtifacts);
        if (plugin.isEmpty()) {
            throw new IllegalStateException("ScenarioMesh plugin runtime artifacts are unavailable; cannot construct isolated worker control classpath");
        }

        LinkedHashSet<Path> target = new LinkedHashSet<>();
        addBuildPath(target, project.getBuild().getTestOutputDirectory());
        addBuildPath(target, project.getBuild().getOutputDirectory());

        Set<Artifact> artifacts = project.getArtifacts() == null
                ? Set.of() : new LinkedHashSet<>(project.getArtifacts());
        if (classpathDependencyScopeExclude != null && !classpathDependencyScopeExclude.isBlank()) {
            artifacts = excludeMatching(artifacts, new ScopeArtifactFilter(classpathDependencyScopeExclude));
        }
        if (classpathDependencyExcludes != null && !classpathDependencyExcludes.isEmpty()) {
            artifacts = excludeMatching(artifacts, new PatternIncludesArtifactFilter(classpathDependencyExcludes));
        }
        for (Artifact artifact : artifacts) {
            File file = artifact.getFile();
            if (file != null) target.add(file.toPath().toAbsolutePath().normalize());
        }

        if (additionalClasspathElements != null) {
            for (String element : additionalClasspathElements) {
                if (element == null || element.isBlank()) continue;
                target.add(Path.of(element).toAbsolutePath().normalize());
            }
        }

        // Adapter implementation jars and optional third-party SPI providers are control artifacts,
        // but current adapter loading requires them in the target realm as well. They are appended
        // after every native executor classpath element so they cannot override project classes.
        target.addAll(plugin);
        return new RuntimeClasspaths(List.copyOf(plugin), List.copyOf(target));
    }

    private Set<Path> pluginClasspath(List<Artifact> pluginArtifacts) {
        Set<Path> plugin = new LinkedHashSet<>();
        if (pluginArtifacts != null) {
            for (Artifact artifact : pluginArtifacts) {
                File file = artifact.getFile();
                if (file != null && file.exists()) plugin.add(file.toPath().toAbsolutePath().normalize());
            }
        }
        return plugin;
    }

    private Set<Artifact> excludeMatching(Set<Artifact> artifacts, ArtifactFilter filter) {
        Set<Artifact> filtered = new LinkedHashSet<>();
        for (Artifact artifact : artifacts) {
            // Surefire's generateTestClasspath uses these include-filters as the set to remove.
            if (!filter.include(artifact)) filtered.add(artifact);
        }
        return filtered;
    }

    private void addBuildPath(Set<Path> target, String value) {
        if (value != null && !value.isBlank()) target.add(Path.of(value).toAbsolutePath().normalize());
    }

    /** Backward-compatible mixed classpath for bootstrap paths that have not yet moved to split launch. */
    List<Path> resolve(MavenProject project, List<Artifact> pluginArtifacts) throws Exception {
        return resolveSplit(project, pluginArtifacts).targetClasspath();
    }

    record RuntimeClasspaths(List<Path> controlClasspath, List<Path> targetClasspath) {
        RuntimeClasspaths {
            controlClasspath = List.copyOf(controlClasspath);
            targetClasspath = List.copyOf(targetClasspath);
            if (controlClasspath.isEmpty()) throw new IllegalArgumentException("control classpath must not be empty");
            if (targetClasspath.isEmpty()) throw new IllegalArgumentException("target classpath must not be empty");
        }
    }
}
