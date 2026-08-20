package io.scenariomesh.cli;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;

final class XmlDocuments {
    private XmlDocuments() {}

    static Document parse(String xml, String description) {
        try {
            return factory().newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse " + description + ": " + exception.getMessage(), exception);
        }
    }

    static Document create(String rootName) {
        try {
            Document document = factory().newDocumentBuilder().newDocument();
            document.appendChild(document.createElement(rootName));
            return document;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create XML document: " + exception.getMessage(), exception);
        }
    }

    static String serialize(Document document) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString().trim() + System.lineSeparator();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to serialize XML: " + exception.getMessage(), exception);
        }
    }

    private static DocumentBuilderFactory factory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }
}
