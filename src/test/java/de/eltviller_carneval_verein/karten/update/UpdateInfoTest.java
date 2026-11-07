package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class UpdateInfoTest {

	/** Auszug aus der metadata.properties von Release 1.0.1. */
	private static final String RELEASE_101 = """
			app.version=1.0.1
			app.revision=0
			app.repositoryUrl=https\\://github.com/SirEddington/Kartenverwaltung/releases/latest/download
			windows.package-family-name=EcvKarten_0cs1gtds5d9x0
			internal.windows.package-family-name-changed=false
			""";

	private static UpdateInfo parse(String text, int maxBytes) throws IOException {
		return UpdateInfo.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)), maxBytes);
	}

	@Test
	void parsesRelease101() throws IOException {
		UpdateInfo info = parse(RELEASE_101, 65536);
		assertEquals(AppVersion.parse("1.0.1", "0").orElseThrow(), info.version());
		assertEquals("EcvKarten_0cs1gtds5d9x0", info.packageFamilyName());
		assertFalse(info.familyChanged());
	}

	@Test
	void readsRevisionAndChangedFlag() throws IOException {
		UpdateInfo info = parse("app.version=1.1.0\napp.revision=2\ninternal.windows.package-family-name-changed=true\n", 65536);
		assertEquals("1.1.0 (Rev. 2)", info.version().display());
		assertEquals(true, info.familyChanged());
	}

	@Test
	void missingVersionIsInvalid() {
		assertThrows(InvalidUpdateDataException.class, () -> parse("app.revision=0\n", 65536));
	}

	@Test
	void invalidVersionIsInvalid() {
		assertThrows(InvalidUpdateDataException.class, () -> parse("app.version=1.x\n", 65536));
	}

	@Test
	void invalidRevisionIsInvalid() {
		assertThrows(InvalidUpdateDataException.class, () -> parse("app.version=1.1.0\napp.revision=-3\n", 65536));
	}

	@Test
	void brokenUnicodeEscapeIsInvalid() {
		assertThrows(InvalidUpdateDataException.class, () -> parse("app.version=\\u12\n", 65536));
	}

	@Test
	void tooLargeIsInvalid() {
		String text = "app.version=1.1.0\n#" + "x".repeat(100);
		assertThrows(InvalidUpdateDataException.class, () -> parse(text, 100));
	}

	@Test
	void missingFamilyIsNull() throws IOException {
		assertNull(parse("app.version=1.1.0\n", 65536).packageFamilyName());
	}
}
