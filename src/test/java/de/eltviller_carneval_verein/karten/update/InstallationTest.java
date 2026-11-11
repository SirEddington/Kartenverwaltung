package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class InstallationTest {

	private static final String FAMILY = "EcvKarten_0cs1gtds5d9x0";
	private static final String PACKAGE_DIR = "C:\\Users\\u\\AppData\\Local\\Packages\\" + FAMILY;

	private static Map<String, String> props(String os) {
		Map<String, String> p = new HashMap<>();
		p.put("app.version", "1.1.0");
		p.put("app.revision", "1");
		p.put("app.repositoryUrl", "https://github.com/SirEddington/Kartenverwaltung/releases/latest/download");
		p.put("app.fsname", "ecv-karten");
		p.put("os.name", os);
		return p;
	}

	private static Optional<Installation> from(Map<String, String> props, Map<String, String> env) {
		return Installation.from(props::get, env::get);
	}

	private static Installation windowsPackage() {
		Map<String, String> p = props("Windows 11");
		p.put("app.windows.userModelID", FAMILY + "!EcvKarten");
		return from(p, Map.of("PACKAGE_LOCALAPPDATA", PACKAGE_DIR)).orElseThrow();
	}

	@Test
	void baseGetsTrailingSlash() {
		Installation inst = from(props("Linux"), Map.of()).orElseThrow();
		assertEquals(URI.create("https://github.com/SirEddington/Kartenverwaltung/releases/latest/download/"), inst.base());
		assertEquals(URI.create("https://github.com/SirEddington/Kartenverwaltung/releases/latest/download/metadata.properties"),
				inst.metadataUri());
		assertEquals(URI.create("https://github.com/SirEddington/Kartenverwaltung/releases/latest/download/ecv-karten.appinstaller"),
				inst.appInstallerUri());
		assertEquals("1.1.0 (Rev. 1)", inst.version().display());
	}

	@Test
	void baseWithSlashStaysUnchanged() {
		Map<String, String> p = props("Linux");
		p.put("app.repositoryUrl", "http://localhost:8765/upd/");
		assertEquals(URI.create("http://localhost:8765/upd/"), from(p, Map.of()).orElseThrow().base());
	}

	@Test
	void missingPropertyGivesEmpty() {
		for (String key : new String[] { "app.version", "app.repositoryUrl", "app.fsname" }) {
			Map<String, String> p = props("Linux");
			p.remove(key);
			assertEquals(Optional.empty(), from(p, Map.of()), key);
		}
	}

	@Test
	void invalidRepositoryUrlGivesEmpty() {
		for (String url : new String[] { "ht tp://x", "relative/path", "file:///C:/x", "https://" }) {
			Map<String, String> p = props("Linux");
			p.put("app.repositoryUrl", url);
			assertEquals(Optional.empty(), from(p, Map.of()), url);
		}
	}

	@Test
	void windowsPackageNeedsFamilyAndPackageLocalAppData() {
		Installation inst = windowsPackage();
		assertEquals(Plattform.WINDOWS_PAKET, inst.plattform());
		assertEquals(Optional.of(FAMILY), inst.packageFamilyName());
	}

	@Test
	void zipWithoutPackageLocalAppDataIsWindowsWithoutPackage() {
		Map<String, String> p = props("Windows 11");
		p.put("app.windows.userModelID", FAMILY + "!EcvKarten");
		assertEquals(Plattform.WINDOWS_OHNE_PAKET, from(p, Map.of()).orElseThrow().plattform());
		assertEquals(Plattform.WINDOWS_OHNE_PAKET, from(props("Windows 10"), Map.of("PACKAGE_LOCALAPPDATA", PACKAGE_DIR))
				.orElseThrow().plattform());
	}

	@Test
	void packageLocalAppDataOfOtherFamilyIsWindowsWithoutPackage() {
		Map<String, String> p = props("Windows 11");
		p.put("app.windows.userModelID", FAMILY + "!EcvKarten");
		Map<String, String> env = Map.of("PACKAGE_LOCALAPPDATA", "C:\\Users\\u\\AppData\\Local\\Packages\\Other_123");
		assertEquals(Plattform.WINDOWS_OHNE_PAKET, from(p, env).orElseThrow().plattform());
	}

	private static UpdateInfo info(String family, boolean changed) {
		return new UpdateInfo(AppVersion.parse("1.1.0", "2").orElseThrow(), family, changed);
	}

	@Test
	void inPlaceWithSameFamily() {
		assertTrue(windowsPackage().canUpdateInPlace(info(FAMILY, false)));
		assertTrue(windowsPackage().canUpdateInPlace(info(FAMILY, true)));
	}

	@Test
	void notInPlaceWithOtherFamily() {
		assertEquals(false, windowsPackage().canUpdateInPlace(info("EcvKarten_neu", false)));
	}

	@Test
	void missingFamilyUsesChangedFlag() {
		assertTrue(windowsPackage().canUpdateInPlace(info(null, false)));
		assertEquals(false, windowsPackage().canUpdateInPlace(info(null, true)));
	}

	@Test
	void notInPlaceWithoutPackage() {
		assertEquals(false, from(props("Linux"), Map.of()).orElseThrow().canUpdateInPlace(info(FAMILY, false)));
		assertEquals(false, from(props("Windows 11"), Map.of()).orElseThrow().canUpdateInPlace(info(FAMILY, false)));
	}

	@Test
	void linuxAndOther() {
		assertEquals(Plattform.LINUX, from(props("Linux"), Map.of()).orElseThrow().plattform());
		assertEquals(Plattform.SONSTIGE, from(props("Mac OS X"), Map.of()).orElseThrow().plattform());
		assertTrue(from(props("Linux"), Map.of()).orElseThrow().packageFamilyName().isEmpty());
	}

	private static Optional<URI> releasePage(String repositoryUrl, String version, String revision) {
		Map<String, String> p = props("Linux");
		p.put("app.repositoryUrl", repositoryUrl);
		return from(p, Map.of()).orElseThrow().releasePage(AppVersion.parse(version, revision).orElseThrow());
	}

	@Test
	void releasePageOnGithub() {
		String source = "https://github.com/SirEddington/Kartenverwaltung/releases/latest/download";
		URI expected = URI.create("https://github.com/SirEddington/Kartenverwaltung/releases/tag/1.2.0");
		assertEquals(Optional.of(expected), releasePage(source, "1.2.0", null));
		assertEquals(Optional.of(expected), releasePage(source, "1.2.0", "2"));
		assertEquals(Optional.of(expected), releasePage(source + "/", "1.2.0", null));
		assertEquals(Optional.of(expected), releasePage(source.replace("github.com", "GitHub.com"), "1.2.0", null));
	}

	@Test
	void noReleasePageElsewhere() {
		for (String source : new String[] { "http://github.com/SirEddington/Kartenverwaltung/releases/latest/download",
				"https://example.org/a/b/releases/latest/download", "https://github.com/a/b/releases/download",
				"https://github.com/a/b/c/releases/latest/download", "https://github.com:8443/a/b/releases/latest/download",
				"https://u@github.com/a/b/releases/latest/download", "https://github.com/a/b/releases/latest/download?x=1",
				"https://github.com/a/b/releases/latest/download#f", "http://localhost:8765/upd" }) {
			assertEquals(Optional.empty(), releasePage(source, "1.2.0", null), source);
		}
	}
}
