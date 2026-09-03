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
        return resolveSplit(project, pluginArtifacts, additionalClasspathElements,
                classpathDependencyExcludes, classpathDependencyScopeExclude, List.of());
    }

    RuntimeClasspaths resolveSplit(MavenProject project,
                                   List<Artifact> pluginArtifacts,
                                   List<String> additionalClasspathElements,
                                   List<String> classpathDependencyExcludes,
                                   String classpathDependencyScopeExclude,
                                   List<String> targetFrameworkClasspathElements) throws Exception {
        boolean targetOwnsJUnitPlatform = targetFrameworkClasspathElements != null
                && !targetFrameworkClasspathElements.isEmpty();
        Set<Path> plugin = pluginClasspath(pluginArtifacts, targetOwnsJUnitPlatform);
        if (plugin.isEmpty()) {
            throw new IllegalStateException("ScenarioMesh plugin runtime artifacts are unavailable; cannot construct isolated worker control classpath");
        }

        // Maven has already resolved the complete test realm, including transitive dependencies
        // and the exact order used by the target build. Reconstructing it from project artifacts
        // loses module dependencies in JPMS projects.
        LinkedHashSet<Path> target = new LinkedHashSet<>();
        for (String element : project.getTestClasspathElements()) addBuildPath(target, element);

        Set<Artifact> allArtifacts = project.getArtifacts() == null
                ? Set.of() : new LinkedHashSet<>(project.getArtifacts());
        Set<Artifact> artifacts = allArtifacts;
        if (classpathDependencyScopeExclude != null && !classpathDependencyScopeExclude.isBlank()) {
            artifacts = excludeMatching(artifacts, new ScopeArtifactFilter(classpathDependencyScopeExclude));
        }
        if (classpathDependencyExcludes != null && !classpathDependencyExcludes.isEmpty()) {
            artifacts = excludeMatching(artifacts, new PatternIncludesArtifactFilter(classpathDependencyExcludes));
        }
        Set<Path> excludedArtifacts = new LinkedHashSet<>();
        for (Artifact artifact : allArtifacts) {
            if (artifacts.contains(artifact)) continue;
            File file = artifact.getFile();
            if (file != null) excludedArtifacts.add(file.toPath().toAbsolutePath().normalize());
        }
        target.removeAll(excludedArtifacts);

        if (additionalClasspathElements != null) {
            for (String element : additionalClasspathElements) {
                if (element == null || element.isBlank()) continue;
                target.add(Path.of(element).toAbsolutePath().normalize());
            }
        }

        if (targetFrameworkClasspathElements != null) {
            for (String element : targetFrameworkClasspathElements) addBuildPath(target, element);
        }

        List<Path> modulePath = List.copyOf(target);
        // Adapter implementation jars and optional third-party SPI providers are control artifacts,
        // but current adapter loading requires them in the target realm as well. They are appended
        // after every native executor classpath element so they cannot override project classes.
        target.addAll(plugin);
        // The worker control plane is launched on -classpath.  It must not also be placed on a
        // target JPMS module path: control jars can carry service metadata that is not a valid
        // automatic module, and Maven does not make plugin implementation artifacts test modules.
        return new RuntimeClasspaths(List.copyOf(plugin), List.copyOf(target), modulePath);
    }

    private Set<Path> pluginClasspath(List<Artifact> pluginArtifacts, boolean targetOwnsJUnitPlatform) {
        Set<Path> plugin = new LinkedHashSet<>();
        if (pluginArtifacts != null) {
            for (Artifact artifact : pluginArtifacts) {
                if (targetOwnsJUnitPlatform && isJUnitPlatformImplementation(artifact)) continue;
                File file = artifact.getFile();
                if (file != null && file.exists()) plugin.add(file.toPath().toAbsolutePath().normalize());
            }
        }
        return plugin;
    }

    private boolean isJUnitPlatformImplementation(Artifact artifact) {
        String group = artifact.getGroupId();
        return "org.junit.platform".equals(group)
                || "org.junit.jupiter".equals(group)
                || "org.junit.vintage".equals(group);
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

    record RuntimeClasspaths(List<Path> controlClasspath, List<Path> targetClasspath, List<Path> targetModulePath) {
        RuntimeClasspaths {
            controlClasspath = List.copyOf(controlClasspath);
            targetClasspath = List.copyOf(targetClasspath);
            targetModulePath = List.copyOf(targetModulePath);
            if (controlClasspath.isEmpty()) throw new IllegalArgumentException("control classpath must not be empty");
            if (targetClasspath.isEmpty()) throw new IllegalArgumentException("target classpath must not be empty");
            if (targetModulePath.isEmpty()) throw new IllegalArgumentException("target module path must not be empty");
        }
    }
}
