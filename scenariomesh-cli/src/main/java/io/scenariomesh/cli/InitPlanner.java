package io.scenariomesh.cli;

import org.w3c.dom.Document;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class InitPlanner {
    private static final String CONFIG_CONTENT = "scenariomesh:\n  configVersion: 1\n";
    private final MavenExtensionXml extensionXml = new MavenExtensionXml();

    InitPlan plan(Path requestedDirectory, String version) throws Exception {
        Path projectDirectory = requestedDirectory.toAbsolutePath().normalize();
        Path pom = projectDirectory.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new IllegalArgumentException("ScenarioMesh init requires a Maven project with pom.xml: " + projectDirectory);
        }
        validatePom(pom);
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("ScenarioMesh version must not be blank");
        }

        List<InitPlan.FileChange> changes = new ArrayList<>();
        Path extensionFile = projectDirectory.resolve(".mvn/extensions.xml");
        String existingExtension = readIfPresent(extensionFile);
        String desiredExtension = extensionXml.desiredContent(existingExtension, version.trim());
        if (!desiredExtension.equals(existingExtension)) {
            changes.add(new InitPlan.FileChange(
                    extensionFile,
                    existingExtension,
                    desiredExtension,
                    existingExtension == null ? InitPlan.ChangeKind.CREATE : InitPlan.ChangeKind.UPDATE));
        }

        Path shortConfig = projectDirectory.resolve("scenariomesh.yml");
        Path longConfig = projectDirectory.resolve("scenariomesh.yaml");
        boolean shortExists = Files.isRegularFile(shortConfig);
        boolean longExists = Files.isRegularFile(longConfig);
        if (shortExists && longExists) {
            throw new IllegalArgumentException("Both scenariomesh.yml and scenariomesh.yaml exist; keep one before running init");
        }
        if (!shortExists && !longExists) {
            changes.add(new InitPlan.FileChange(
                    shortConfig,
                    null,
                    CONFIG_CONTENT,
                    InitPlan.ChangeKind.CREATE));
        }

        return new InitPlan(projectDirectory, changes);
    }

    private void validatePom(Path pom) throws Exception {
        String xml = Files.readString(pom, StandardCharsets.UTF_8);
        Document document = XmlDocuments.parse(xml, "pom.xml");
        if (document.getDocumentElement() == null || !"project".equals(document.getDocumentElement().getNodeName())) {
            throw new IllegalArgumentException("pom.xml root element must be <project>");
        }
    }

    private String readIfPresent(Path path) throws Exception {
        return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
    }
}
