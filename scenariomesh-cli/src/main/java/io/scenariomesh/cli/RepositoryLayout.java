package io.scenariomesh.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Repository-aware filesystem discovery used by init. */
final class RepositoryLayout {
    Path repositoryRoot(Path projectDirectory) {
        Path current = projectDirectory.toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve(".git"))) {
                return candidate;
            }
        }
        return current;
    }

    List<Path> extensionFiles(Path repositoryRoot) throws IOException {
        List<Path> result = new ArrayList<>();
        try (var stream = Files.walk(repositoryRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> "extensions.xml".equals(path.getFileName().toString()))
                    .filter(path -> path.getParent() != null && ".mvn".equals(path.getParent().getFileName().toString()))
                    .filter(path -> !containsSegment(path, ".git"))
                    .filter(path -> !containsSegment(path, "target"))
                    .forEach(path -> result.add(path.toAbsolutePath().normalize()));
        }
        result.sort(Comparator.comparing(Path::toString));
        return List.copyOf(result);
    }

    Path effectiveExtensionFile(Path projectDirectory, List<Path> discovered) {
        Path project = projectDirectory.toAbsolutePath().normalize();
        Path best = null;
        int bestDepth = -1;
        for (Path file : discovered) {
            Path mvnDirectory = file.getParent();
            Path owner = mvnDirectory == null ? null : mvnDirectory.getParent();
            if (owner != null && project.startsWith(owner) && owner.getNameCount() > bestDepth) {
                best = file;
                bestDepth = owner.getNameCount();
            }
        }
        return best == null ? project.resolve(".mvn/extensions.xml") : best;
    }

    private boolean containsSegment(Path path, String segment) {
        for (Path part : path) {
            if (segment.equals(part.toString())) return true;
        }
        return false;
    }
}
