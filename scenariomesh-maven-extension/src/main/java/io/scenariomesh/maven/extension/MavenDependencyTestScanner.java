package io.scenariomesh.maven.extension;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Selects the resolved project artifacts that Surefire scans for dependency test classes. */
final class MavenDependencyTestScanner {
    List<String> resolve(MavenProject project, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return List.of();
        Set<String> selected = new LinkedHashSet<>();
        for (String pattern : patterns) {
            ArtifactPattern parsed = ArtifactPattern.parse(pattern);
            for (Artifact artifact : project.getArtifacts()) {
                if (!parsed.matches(artifact)) continue;
                File file = artifact.getFile();
                if (file == null || !file.exists()) {
                    throw new IllegalStateException("dependenciesToScan matched unresolved artifact " + artifact);
                }
                selected.add(file.toPath().toAbsolutePath().normalize().toString());
            }
        }
        return List.copyOf(selected);
    }

    private record ArtifactPattern(List<String> parts) {
        static ArtifactPattern parse(String raw) {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("dependenciesToScan contains a blank pattern");
            String[] values = raw.trim().split(":", -1);
            if (values.length > 5) throw new IllegalArgumentException("dependenciesToScan pattern has more than five coordinates: " + raw);
            List<String> parts = new ArrayList<>(List.of(values));
            for (String value : parts) if (value.isEmpty()) throw new IllegalArgumentException("dependenciesToScan pattern contains an empty coordinate: " + raw);
            return new ArtifactPattern(List.copyOf(parts));
        }

        boolean matches(Artifact artifact) {
            return part(0, artifact.getGroupId())
                    && part(1, artifact.getArtifactId())
                    && part(2, artifact.getType())
                    && part(3, artifact.getClassifier() == null ? "" : artifact.getClassifier())
                    && part(4, artifact.getVersion());
        }

        private boolean part(int index, String value) {
            if (index >= parts.size()) return true;
            String glob = parts.get(index).replace(".", "\\.").replace("*", ".*");
            return value != null && value.matches(glob);
        }
    }
}
