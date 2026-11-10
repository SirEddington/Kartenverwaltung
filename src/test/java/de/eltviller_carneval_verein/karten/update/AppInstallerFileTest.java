package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AppInstallerFileTest {

	private static final String PUBLISHER = "CN=Kartenverwaltung - Eltviller Carneval Verein";

	private static byte[] probeFile() throws IOException {
		try (InputStream in = AppInstallerFileTest.class.getResourceAsStream("ecv-karten-fx132.appinstaller")) {
			return in.readAllBytes();
		}
	}

	private static byte[] xml(String mainPackage) {
		return ("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
				+ "<AppInstaller xmlns=\"http://schemas.microsoft.com/appx/appinstaller/2018\" Version=\"1.1.0.3\""
				+ " Uri=\"http://localhost:8765/p132/ecv-karten-fx132.appinstaller\">" + mainPackage + "</AppInstaller>")
				.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void readsProbeFile() throws IOException {
		AppInstallerFile f = AppInstallerFile.parse(probeFile());
		assertEquals("EcvKartenFx132", f.name());
		assertEquals(PUBLISHER, f.publisher());
		assertEquals("1.1.0 (Rev. 3)", f.version().display());
		assertTrue(f.uri().toString().endsWith("ecv-karten-fx132-1.1.0-3.x64.msix"), f.uri().toString());
	}

	@Test
	void bomIsAccepted() throws IOException {
		byte[] plain = probeFile();
		byte[] withBom = new byte[plain.length + 3];
		withBom[0] = (byte) 0xEF;
		withBom[1] = (byte) 0xBB;
		withBom[2] = (byte) 0xBF;
		System.arraycopy(plain, 0, withBom, 3, plain.length);
		assertEquals("EcvKartenFx132", AppInstallerFile.parse(withBom).name());
	}

	@Test
	void missingMainPackageIsInvalid() {
		assertThrows(InvalidUpdateDataException.class, () -> AppInstallerFile.parse(xml("")));
	}

	@Test
	void missingAttributeIsInvalid() {
		assertThrows(InvalidUpdateDataException.class, () -> AppInstallerFile.parse(
				xml("<MainPackage Name=\"EcvKartenFx132\" Publisher=\"" + PUBLISHER + "\" Version=\"1.1.0.3\"/>")));
	}

	@Test
	void invalidVersionIsInvalid() {
		assertThrows(InvalidUpdateDataException.class, () -> AppInstallerFile.parse(xml("<MainPackage Name=\"A\" Publisher=\""
				+ PUBLISHER + "\" Version=\"1.1.0\" Uri=\"http://localhost/a.msix\"/>")));
	}

	@Test
	void doctypeIsRejected() {
		byte[] evil = ("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///c:/windows/win.ini\">]>"
				+ "<AppInstaller><MainPackage Name=\"&e;\" Publisher=\"p\" Version=\"1.1.0.3\" Uri=\"http://localhost/a.msix\"/>"
				+ "</AppInstaller>").getBytes(StandardCharsets.UTF_8);
		assertThrows(InvalidUpdateDataException.class, () -> AppInstallerFile.parse(evil));
	}

	@Test
	void notXmlIsInvalid() {
		assertThrows(InvalidUpdateDataException.class,
				() -> AppInstallerFile.parse("<html><body>Login</body>".getBytes(StandardCharsets.UTF_8)));
	}
}
