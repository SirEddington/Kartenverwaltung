package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MsixManifestTest {

	@TempDir
	Path dir;

	@Test
	void readsProbeManifest() throws IOException {
		MsixManifest m = MsixManifest.read(TestMsix.standard(dir.resolve("a.msix"), "1.1.0.3"));
		assertEquals("EcvKartenFx132", m.name());
		assertEquals("CN=Kartenverwaltung - Eltviller Carneval Verein", m.publisher());
		assertEquals("1.1.0 (Rev. 3)", m.version().display());
		assertEquals("x64", m.architecture());
		assertEquals("EcvKartenFx132_0cs1gtds5d9x0", m.packageFamilyName());
	}

	@Test
	void missingManifestIsInvalid() throws IOException {
		Path f = TestMsix.write(dir.resolve("a.msix"), null, TestMsix.standardExtras());
		assertThrows(InvalidUpdateDataException.class, () -> MsixManifest.read(f));
	}

	@Test
	void truncatedZipIsInvalid() throws IOException {
		Path f = TestMsix.standard(dir.resolve("a.msix"), "1.1.0.3");
		try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
			raf.setLength(raf.length() / 2);
		}
		assertThrows(IOException.class, () -> MsixManifest.read(f));
	}

	@Test
	void oversizedManifestIsInvalid() throws IOException {
		String big = TestMsix.manifest("1.1.0.3").replace("</Package>", "<!--" + "x".repeat(1_100_000) + "--></Package>");
		Path f = TestMsix.write(dir.resolve("a.msix"), big, TestMsix.standardExtras());
		assertThrows(InvalidUpdateDataException.class, () -> MsixManifest.read(f));
	}

	@Test
	void doctypeInManifestIsRejected() throws IOException {
		String evil = "<?xml version=\"1.0\"?><!DOCTYPE p [<!ENTITY e SYSTEM \"file:///c:/windows/win.ini\">]>"
				+ TestMsix.manifest("1.1.0.3").replaceFirst("<\\?xml[^>]*\\?>", "");
		Path f = TestMsix.write(dir.resolve("a.msix"), evil, TestMsix.standardExtras());
		assertThrows(InvalidUpdateDataException.class, () -> MsixManifest.read(f));
	}

	@Test
	void missingIdentityIsInvalid() throws IOException {
		String noId = TestMsix.manifest("1.1.0.3").replaceFirst("<Identity [^>]*/>", "");
		Path f = TestMsix.write(dir.resolve("a.msix"), noId, TestMsix.standardExtras());
		assertThrows(InvalidUpdateDataException.class, () -> MsixManifest.read(f));
	}

	@Test
	void notAZipIsInvalid() throws IOException {
		Path f = Files.writeString(dir.resolve("a.msix"), "kein zip");
		assertThrows(IOException.class, () -> MsixManifest.read(f));
	}

	@Test
	void verifyCompleteDetectsFlippedByte() throws IOException {
		Path f = TestMsix.standard(dir.resolve("a.msix"), "1.1.0.3");
		TestMsix.flipPayloadByte(f);
		assertThrows(IOException.class, () -> MsixManifest.verifyComplete(f));
	}

	@Test
	void verifyCompleteRequiresSignatureAndBlockMap() throws IOException {
		Map<String, byte[]> noSignature = TestMsix.standardExtras();
		noSignature.remove("AppxSignature.p7x");
		Path a = TestMsix.write(dir.resolve("a.msix"), TestMsix.manifest("1.1.0.3"), noSignature);
		assertThrows(InvalidUpdateDataException.class, () -> MsixManifest.verifyComplete(a));
		Map<String, byte[]> noBlockMap = TestMsix.standardExtras();
		noBlockMap.remove("AppxBlockMap.xml");
		Path b = TestMsix.write(dir.resolve("b.msix"), TestMsix.manifest("1.1.0.3"), noBlockMap);
		assertThrows(InvalidUpdateDataException.class, () -> MsixManifest.verifyComplete(b));
	}

	@Test
	void verifyCompleteAcceptsStandard() throws IOException {
		Path f = TestMsix.standard(dir.resolve("a.msix"), "1.1.0.3");
		assertDoesNotThrow(() -> MsixManifest.verifyComplete(f));
	}

	/** code-review: eine Zip-Bombe darf den Takt nicht beliebig lange binden. */
	@Test
	void verifyCompleteStopsAtUnpackedLimit() throws IOException {
		Path f = TestMsix.standard(dir.resolve("a.msix"), "1.1.0.3");
		assertThrows(InvalidUpdateDataException.class, () -> MsixManifest.verifyComplete(f, 500));
		assertDoesNotThrow(() -> MsixManifest.verifyComplete(f, 1_000_000));
	}
}
