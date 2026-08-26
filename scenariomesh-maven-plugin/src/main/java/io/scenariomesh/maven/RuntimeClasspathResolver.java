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
    List<Path> resolve(MavenProject project, List<Artifact> pluginArtifacts) throws Exception {
        Set<Path> paths = new LinkedHashSet<>();
        for (String element : project.getTestClasspathElements()) {
            paths.add(Path.of(element).toAbsolutePath().normalize());
        }
        if (pluginArtifacts != null) {
            for (Artifact artifact : pluginArtifacts) {
                File file = artifact.getFile();
                if (file != null && file.exists()) {
                    paths.add(file.toPath().toAbsolutePath().normalize());
                }
            }
        }
        return List.copyOf(paths);
    }
}
