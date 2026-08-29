package io.scenariomesh.core;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architectural fitness functions for the innermost ScenarioMesh module.
 *
 * <p>Core is deliberately framework/build-tool neutral. These tests make that
 * boundary executable so future changes cannot silently pull Maven, test-engine,
 * transport, serialization, observability, or reporting implementations inward.</p>
 */
final class CoreArchitectureFitnessTest {
    private static final List<String> FORBIDDEN_PRODUCTION_REFERENCES = List.of(
            "org.apache.maven",
            "org.junit",
            "io.cucumber",
            "org.testng",
            "com.fasterxml.jackson",
            "org.yaml.snakeyaml",
            "io.opentelemetry",
            "java.net.Socket",
            "java.net.ServerSocket");

    @Test
    void productionSourcesRemainTechnologyNeutral() throws Exception {
        Path mainSources = Path.of("src", "main", "java");
        try (var files = Files.walk(mainSources)) {
            for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                for (String forbidden : FORBIDDEN_PRODUCTION_REFERENCES) {
                    assertTrue(!text.contains(forbidden),
                            () -> source + " leaks outer-layer technology into scenariomesh-core: " + forbidden);
                }
            }
        }
    }

    @Test
    void coreHasNoProductionScopedExternalDependencies() throws Exception {
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var dependencies = document.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            var dependency = dependencies.item(i);
            var children = dependency.getChildNodes();
            String scope = null;
            String groupId = null;
            String artifactId = null;
            for (int child = 0; child < children.getLength(); child++) {
                var node = children.item(child);
                switch (node.getNodeName()) {
                    case "scope" -> scope = node.getTextContent().trim();
                    case "groupId" -> groupId = node.getTextContent().trim();
                    case "artifactId" -> artifactId = node.getTextContent().trim();
                    default -> { }
                }
            }
            String coordinate = groupId + ":" + artifactId;
            assertTrue("test".equals(scope),
                    () -> "scenariomesh-core must not gain production-scoped external dependency " + coordinate);
        }
    }
}
