package io.scenariomesh.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.workerruntime.JsonCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/** Collects artifact references from installed providers and writes a deterministic manifest. */
final class ReportArtifacts {
    private ReportArtifacts() {}

    static List<ReportArtifact> collect(ReportExportContext baseContext) throws Exception {
        List<ReportArtifact> artifacts = new ArrayList<>();
        Set<String> providerIds = new LinkedHashSet<>();
        Set<String> artifactIds = new LinkedHashSet<>();
        try {
            for (ReportArtifactProvider provider : ServiceLoader.load(
                    ReportArtifactProvider.class, Thread.currentThread().getContextClassLoader())) {
                String providerId = requireId(provider.id(), provider.getClass().getName());
                if (!providerIds.add(providerId)) {
                    throw new IllegalStateException("Duplicate ScenarioMesh report artifact provider id '" + providerId + "'");
                }
                List<ReportArtifact> provided = provider.artifacts(baseContext);
                if (provided == null) continue;
                for (ReportArtifact artifact : provided) {
                    if (artifact == null) throw new IllegalStateException("ScenarioMesh report artifact provider '"
                            + providerId + "' returned a null artifact");
                    if (!artifactIds.add(artifact.id())) {
                        throw new IllegalStateException("Duplicate ScenarioMesh report artifact id '" + artifact.id() + "'");
                    }
                    artifacts.add(artifact);
                }
            }
        } catch (ServiceConfigurationError error) {
            throw new IllegalStateException("ScenarioMesh report artifact SPI could not load a provider: "
                    + error.getMessage(), error);
        }
        return List.copyOf(artifacts);
    }

    static Path writeManifest(Path reportingDirectory, List<ReportArtifact> artifacts) throws Exception {
        Files.createDirectories(reportingDirectory);
        Path output = reportingDirectory.resolve("artifacts.json");
        ObjectMapper mapper = JsonCodec.create();
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), new ArtifactManifest(1, artifacts));
        return output;
    }

    private static String requireId(String id, String type) {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("ScenarioMesh report artifact provider " + type + " returned a blank id");
        }
        return id.trim();
    }

    record ArtifactManifest(int version, List<ReportArtifact> artifacts) {
        ArtifactManifest { artifacts = List.copyOf(artifacts == null ? List.of() : artifacts); }
    }
}
