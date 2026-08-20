package io.scenariomesh.cli;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class MavenExtensionXml {
    private static final String GROUP_ID = "io.scenariomesh";
    private static final String ARTIFACT_ID = "scenariomesh-maven-extension";

    String desiredContent(String existing, String version) {
        Document document = existing == null
                ? XmlDocuments.create("extensions")
                : XmlDocuments.parse(existing, ".mvn/extensions.xml");
        Element root = document.getDocumentElement();
        if (root == null || !"extensions".equals(root.getNodeName())) {
            throw new IllegalArgumentException(".mvn/extensions.xml root element must be <extensions>");
        }

        Element match = null;
        NodeList extensions = root.getElementsByTagName("extension");
        for (int i = 0; i < extensions.getLength(); i++) {
            Element extension = (Element) extensions.item(i);
            if (GROUP_ID.equals(childText(extension, "groupId"))
                    && ARTIFACT_ID.equals(childText(extension, "artifactId"))) {
                if (match != null) {
                    throw new IllegalArgumentException(".mvn/extensions.xml contains duplicate ScenarioMesh extension entries");
                }
                match = extension;
            }
        }

        if (match == null) {
            match = document.createElement("extension");
            append(document, match, "groupId", GROUP_ID);
            append(document, match, "artifactId", ARTIFACT_ID);
            append(document, match, "version", version);
            root.appendChild(match);
        } else {
            Element versionElement = child(match, "version");
            String existingVersion = versionElement == null ? null : versionElement.getTextContent().trim();
            if (version.equals(existingVersion) && existing != null) {
                return existing;
            }
            if (versionElement == null) {
                append(document, match, "version", version);
            } else {
                versionElement.setTextContent(version);
            }
        }
        return XmlDocuments.serialize(document);
    }

    private void append(Document document, Element parent, String name, String value) {
        Element child = document.createElement(name);
        child.setTextContent(value);
        parent.appendChild(child);
    }

    private String childText(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? null : child.getTextContent().trim();
    }

    private Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getNodeName())) {
                return element;
            }
        }
        return null;
    }
}
