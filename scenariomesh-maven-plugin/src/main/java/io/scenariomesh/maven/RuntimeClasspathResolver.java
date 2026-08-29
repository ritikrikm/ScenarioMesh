package io.scenariomesh.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shared runtime/test classpath assembly used by preflight and execution. */
final class RuntimeClasspathResolver {
    RuntimeClasspaths resolveSplit(MavenProject project, List<Artifact> pluginArtifacts) throws Exception {
        Set<Path> plugin = new LinkedHashSet<>();
        if (pluginArtifacts != null) {
            for (Artifact artifact : pluginArtifacts) {
                File file = artifact.getFile();
                if (file != null && file.exists()) plugin.add(file.toPath().toAbsolutePath().normalize());
            }
        }
        if (plugin.isEmpty()) {
            throw new IllegalStateException("ScenarioMesh plugin runtime artifacts are unavailable; cannot construct isolated worker control classpath");
        }

        Set<Path> target = new LinkedHashSet<>();
        for (String element : project.getTestClasspathElements()) {
            target.add(Path.of(element).toAbsolutePath().normalize());
        }
        // Adapter implementation jars and optional third-party SPI providers are plugin artifacts,
        // but they must be visible inside the target execution realm as well as to the control JVM.
        target.addAll(plugin);
        return new RuntimeClasspaths(List.copyOf(plugin), List.copyOf(target));
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
