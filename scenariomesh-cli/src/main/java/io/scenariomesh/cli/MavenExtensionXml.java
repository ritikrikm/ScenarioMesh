package io.scenariomesh.cli;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;

import org.xml.sax.InputSource;

final class MavenExtensionXml {
    private static final String GROUP_ID = "io.scenariomesh";
    private static final String ARTIFACT_ID = "scenariomesh-maven-extension";

    String desiredContent(String existing, String version) {
        try {
            Document document = existing == null ? newDocument() : parse(existing);
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
            return serialize(document);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to process .mvn/extensions.xml: " + exception.getMessage(), exception);
        }
    }

    private Document newDocument() throws Exception {
        Document document = factory().newDocumentBuilder().newDocument();
        document.appendChild(document.createElement("extensions"));
        return document;
    }

    private Document parse(String xml) throws Exception {
        return factory().newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private DocumentBuilderFactory factory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
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

    private String serialize(Document document) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        var transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        String result = writer.toString().trim();
        return result + System.lineSeparator();
    }
}
