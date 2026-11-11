package de.eltviller_carneval_verein.karten.update;

import java.net.URI;
import java.net.URISyntaxException;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Das Hauptpaket einer {@code .appinstaller}-Datei (KARV-132): Name, Publisher, Version und Adresse der {@code .msix}.
 *
 * @param version aus der Paketversion {@code a.b.c.d} umgerechnet ({@link AppVersion#fromPackageVersion})
 */
public record AppInstallerFile(String name, String publisher, AppVersion version, URI uri) {

	/** @throws InvalidUpdateDataException wenn {@code MainPackage} oder eins seiner Attribute fehlt oder ungültig ist */
	public static AppInstallerFile parse(byte[] xml) throws InvalidUpdateDataException {
		NodeList list = SecureXml.parse(xml).getElementsByTagNameNS("*", "MainPackage");
		if (list.getLength() != 1) {
			throw new InvalidUpdateDataException("Kein eindeutiges MainPackage in der .appinstaller-Datei");
		}
		Element main = (Element) list.item(0);
		String name = required(main, "Name");
		String publisher = required(main, "Publisher");
		String version = required(main, "Version");
		String uri = required(main, "Uri");
		AppVersion v = AppVersion.fromPackageVersion(version)
				.orElseThrow(() -> new InvalidUpdateDataException("Ungültige Paketversion: " + version));
		try {
			return new AppInstallerFile(name, publisher, v, new URI(uri));
		} catch (URISyntaxException e) {
			throw new InvalidUpdateDataException("Ungültige Adresse des Pakets: " + uri, e);
		}
	}

	private static String required(Element e, String attribute) throws InvalidUpdateDataException {
		String value = e.getAttribute(attribute).strip();
		if (value.isEmpty()) {
			throw new InvalidUpdateDataException("MainPackage ohne " + attribute);
		}
		return value;
	}
}
