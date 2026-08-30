package io.scenariomesh.maven;

import org.apache.maven.project.MavenProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves directories that contain compiled tests owned by the current Maven project.
 * The runtime classpath is intentionally broader; discovery roots must not scan dependency
 * directories or the project's main output as if they were local tests.
 */
final class TestRootResolver {
    List<Path> resolve(MavenProject project) throws Exception {
        return resolve(project, List.of());
    }

    List<Path> resolve(MavenProject project, List<String> dependencyTestRoots) throws Exception {
        Set<Path> roots = new LinkedHashSet<>();
        Path buildDirectory = normalize(project.getBuild().getDirectory());
        Path mainOutput = normalize(project.getBuild().getOutputDirectory());
        Path standardTestOutput = normalize(project.getBuild().getTestOutputDirectory());

        addIfDirectory(roots, standardTestOutput);
        for (String element : project.getTestClasspathElements()) {
            Path candidate = normalize(element);
            if (!Files.isDirectory(candidate)) {
                continue;
            }
            if (!candidate.startsWith(buildDirectory)) {
                continue;
            }
            if (candidate.startsWith(mainOutput)) {
                continue;
            }
            roots.add(candidate);
        }
        for (String element : dependencyTestRoots == null ? List.<String>of() : dependencyTestRoots) {
            Path candidate = normalize(element);
            if (Files.isDirectory(candidate) || (Files.isRegularFile(candidate) && candidate.toString().endsWith(".jar"))) {
                roots.add(candidate);
            }
        }
        return List.copyOf(roots);
    }

    private void addIfDirectory(Set<Path> roots, Path path) {
        if (Files.isDirectory(path)) {
            roots.add(path);
        }
    }

    private Path normalize(String path) {
        return Path.of(path).toAbsolutePath().normalize();
    }
}
