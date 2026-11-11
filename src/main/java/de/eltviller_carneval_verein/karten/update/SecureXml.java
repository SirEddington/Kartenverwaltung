package de.eltviller_carneval_verein.karten.update;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * Liest XML aus Dateien, die jeder mit Schreibrecht auf den gemeinsamen Ordner unterschieben kann (KARV-132):
 * namespace-aware, ohne DOCTYPE, ohne externe Entitäten, mit {@code FEATURE_SECURE_PROCESSING}. Ein BOM am Anfang wird
 * überlesen.
 */
final class SecureXml {

	private SecureXml() {
	}

	/** @throws InvalidUpdateDataException wenn es kein wohlgeformtes XML ist oder einen DOCTYPE hat */
	static Document parse(byte[] xml) throws InvalidUpdateDataException {
		int start = xml.length >= 3 && (xml[0] & 0xFF) == 0xEF && (xml[1] & 0xFF) == 0xBB && (xml[2] & 0xFF) == 0xBF ? 3 : 0;
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setNamespaceAware(true);
			f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			f.setFeature("http://xml.org/sax/features/external-general-entities", false);
			f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			f.setXIncludeAware(false);
			f.setExpandEntityReferences(false);
			DocumentBuilder b = f.newDocumentBuilder();
			b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
				@Override
				public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
					throw e;
				}
			});
			return b.parse(new ByteArrayInputStream(xml, start, xml.length - start));
		} catch (SAXException | IOException e) {
			throw new InvalidUpdateDataException("Kein gültiges XML: " + e.getMessage(), e);
		} catch (ParserConfigurationException e) {
			throw new IllegalStateException("XML-Parser lässt sich nicht sicher einstellen", e);
		}
	}
}
