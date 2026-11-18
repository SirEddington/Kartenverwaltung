package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.store.CloudFolders.CloudTarget;

class CloudFoldersTest {

	private static final String CFG = """
			[General]
			overrideServerUrl=
			[Accounts]
			0\\url=https://cloud.example.de
			0\\Folders\\1\\localPath=D:/ECV/
			0\\Folders\\1\\targetPath=/
			[Settings]
			""";

	private static final CloudTarget EXAMPLE = new CloudTarget("cloud.example.de", 443);

	private static CloudFolders withCfg(String cfg) {
		return new CloudFolders(() -> List.of(cfg), name -> null);
	}

	private static CloudFolders withEnv(Map<String, String> env) {
		return new CloudFolders(List::of, env::get);
	}

	@Test
	void folderInsideANextcloudSyncFolderNamesItsServer() {
		CloudFolders folders = withCfg(CFG);

		assertEquals(Optional.of(EXAMPLE), folders.find("D:\\ECV\\Kartenvorverkauf\\Software"));
		assertEquals(Optional.of(EXAMPLE), folders.find("D:/ECV"), "die Wurzel selbst zählt mit");
	}

	@Test
	void comparisonIgnoresCaseAndSeparators() {
		assertEquals(Optional.of(EXAMPLE), withCfg(CFG).find("d:\\ecv\\karten\\"));
	}

	@Test
	void folderOutsideOrWithSamePrefixIsNoCloudFolder() {
		CloudFolders folders = withCfg(CFG);

		assertEquals(Optional.empty(), folders.find("D:\\ECV-Archiv\\Karten"), "nur an Ordnergrenzen");
		assertEquals(Optional.empty(), folders.find("D:\\Kartenverwaltung\\Test"));
		assertEquals(Optional.empty(), folders.find("\\\\server\\karten"));
	}

	@Test
	void eachFolderBelongsToTheUrlOfItsAccount() {
		String cfg = """
				[Accounts]
				0\\url=https://eins.example.de
				0\\Folders\\1\\localPath=C:/Users/x/Nextcloud/
				1\\url=http://zwei.example.de:8080/nextcloud
				1\\FoldersWithPlaceholders\\3\\localPath=E:/Verein/
				1\\Multifolders\\2\\localPath=F:/Mehr/
				""";
		CloudFolders folders = withCfg(cfg);

		assertEquals(Optional.of(new CloudTarget("eins.example.de", 443)),
				folders.find("C:\\Users\\x\\Nextcloud\\Karten"));
		CloudTarget zwei = new CloudTarget("zwei.example.de", 8080);
		assertEquals(Optional.of(zwei), folders.find("E:\\Verein\\Karten"), "virtuelle Dateien");
		assertEquals(Optional.of(zwei), folders.find("F:\\Mehr"));
	}

	@Test
	void httpWithoutPortUsesPort80() {
		String cfg = "[Accounts]\n0\\url=http://lan-cloud\n0\\Folders\\1\\localPath=D:/LAN/\n";

		assertEquals(Optional.of(new CloudTarget("lan-cloud", 80)), withCfg(cfg).find("D:\\LAN"));
	}

	@Test
	void quotedValuesAndQtEscapesAreDecoded() {
		String cfg = "[Accounts]\n0\\url=\"https://cloud.example.de\"\n"
				+ "0\\Folders\\1\\localPath=\"D:/Vereinsb\\xfcro, Karten/\"\n";
		CloudFolders folders = withCfg(cfg);

		assertEquals(Optional.of(EXAMPLE), folders.find("D:\\Vereinsbüro, Karten\\Saal"));
	}

	@Test
	void byteOrderMarkBeforeTheAccountsSectionIsIgnored() {
		String cfg = "\uFEFF[Accounts]\n0\\url=https://cloud.example.de\n0\\Folders\\1\\localPath=D:/ECV/\n";

		assertEquals(Optional.of(EXAMPLE), withCfg(cfg).find("D:\\ECV"));
	}

	@Test
	void internationalizedDomainIsConvertedForTheCheck() {
		String cfg = "[Accounts]\n0\\url=https://cloud.m\u00fcller.de/nextcloud\n0\\Folders\\1\\localPath=D:/ECV/\n";

		assertEquals(Optional.of(new CloudTarget("cloud.xn--mller-kva.de", 443)),
				withCfg(cfg).find("D:\\ECV"));
	}

	@Test
	void keysOutsideTheAccountsSectionAreIgnored() {
		String cfg = "[Other]\n0\\url=https://cloud.example.de\n0\\Folders\\1\\localPath=D:/ECV/\n";

		assertEquals(Optional.empty(), withCfg(cfg).find("D:\\ECV"));
	}

	@Test
	void folderWithoutUsableUrlIsNoCloudFolder() {
		String cfg = "[Accounts]\n0\\url=kaputt ohne schema\n0\\Folders\\1\\localPath=D:/ECV/\n"
				+ "1\\Folders\\1\\localPath=E:/OhneUrl/\n";
		CloudFolders folders = withCfg(cfg);

		assertEquals(Optional.empty(), folders.find("D:\\ECV"));
		assertEquals(Optional.empty(), folders.find("E:\\OhneUrl"));
	}

	@Test
	void missingOrBrokenConfigurationMeansNoCloudFolder() {
		assertEquals(Optional.empty(), new CloudFolders(List::of, name -> null).find("D:\\ECV"));
		assertEquals(Optional.empty(), new CloudFolders(() -> {
			throw new IllegalStateException("halb geschrieben");
		}, name -> null).find("D:\\ECV"));
		assertEquals(Optional.empty(), withCfg("\u0000\u0001 Müll [").find("D:\\ECV"));
	}

	@Test
	void oneDriveFoldersComeFromTheEnvironment() {
		CloudFolders folders = withEnv(Map.of("OneDriveConsumer", "C:\\Users\\x\\OneDrive", "OneDriveCommercial",
				"C:\\Users\\x\\OneDrive - Verein", "OneDrive", "C:\\Users\\x\\OneDrive"));

		assertEquals(Optional.of(new CloudTarget(CloudFolders.ONEDRIVE_PERSONAL_HOST, 443)),
				folders.find("C:\\Users\\x\\OneDrive\\Karten"));
		assertEquals(Optional.of(new CloudTarget(CloudFolders.ONEDRIVE_BUSINESS_HOST, 443)),
				folders.find("C:\\Users\\x\\OneDrive - Verein\\Karten"));
	}

	@Test
	void plainOneDriveVariableCountsAsPersonal() {
		CloudFolders folders = withEnv(Map.of("OneDrive", "C:\\Users\\x\\OneDrive"));

		assertEquals(Optional.of(new CloudTarget(CloudFolders.ONEDRIVE_PERSONAL_HOST, 443)),
				folders.find("C:\\Users\\x\\OneDrive\\Karten"));
	}

	@Test
	void oneDriveVariableEqualToBusinessCountsAsBusiness() {
		CloudFolders folders = withEnv(Map.of("OneDrive", "C:\\Users\\x\\OneDrive - Verein", "OneDriveCommercial",
				"C:\\Users\\x\\OneDrive - Verein"));

		assertEquals(Optional.of(new CloudTarget(CloudFolders.ONEDRIVE_BUSINESS_HOST, 443)),
				folders.find("C:\\Users\\x\\OneDrive - Verein\\Karten"));
	}

	@Test
	void blankEnvironmentValuesAreIgnored() {
		assertEquals(Optional.empty(), withEnv(Map.of("OneDrive", "  ")).find("C:\\Karten"));
	}

	@Test
	void theLongestMatchingRootWins() {
		String cfg = "[Accounts]\n0\\url=https://cloud.example.de\n0\\Folders\\1\\localPath=C:/Users/x/OneDrive/Nextcloud/\n";
		CloudFolders folders = new CloudFolders(() -> List.of(cfg), Map.of("OneDrive", "C:\\Users\\x\\OneDrive")::get);

		assertEquals(Optional.of(new CloudTarget("cloud.example.de", 443)),
				folders.find("C:\\Users\\x\\OneDrive\\Nextcloud\\Karten"));
		assertEquals(CloudFolders.ONEDRIVE_PERSONAL_HOST, folders.find("C:\\Users\\x\\OneDrive\\Anderes").orElseThrow().host());
	}

	// --- KARV-179: Wo die Konfiguration von Nextcloud liegt

	private static Path cfgIn(String dir) {
		return Path.of(dir).resolve("Nextcloud").resolve("nextcloud.cfg");
	}

	@Test
	void underWindowsTheConfigurationLiesInAppData() {
		String appData = "C:\\Users\\x\\AppData\\Roaming";

		assertEquals(List.of(cfgIn(appData)),
				CloudFolders.nextcloudConfigFiles(Map.of("APPDATA", appData)::get, "C:\\Users\\x"));
	}

	private static final Path FLATPAK_CFG = cfgIn("/home/x/.var/app/com.nextcloud.desktopclient.nextcloud/config");
	private static final List<Path> LINUX_DEFAULT = List.of(cfgIn("/home/x/.config"), FLATPAK_CFG);

	@Test
	void underLinuxTheConfigurationLiesInDotConfigAndThenInTheFlatpak() {
		assertEquals(LINUX_DEFAULT, CloudFolders.nextcloudConfigFiles(name -> null, "/home/x"));
	}

	@Test
	void anAbsoluteXdgConfigHomeReplacesDotConfig() {
		assertEquals(List.of(cfgIn("/daten/cfg"), FLATPAK_CFG),
				CloudFolders.nextcloudConfigFiles(Map.of("XDG_CONFIG_HOME", "/daten/cfg")::get, "/home/x"));
	}

	@Test
	void aRelativeOrBlankXdgConfigHomeCountsAsUnset() {
		assertEquals(LINUX_DEFAULT, CloudFolders.nextcloudConfigFiles(Map.of("XDG_CONFIG_HOME", "cfg")::get, "/home/x"));
		assertEquals(LINUX_DEFAULT, CloudFolders.nextcloudConfigFiles(Map.of("XDG_CONFIG_HOME", "")::get, "/home/x"));
	}

	@Test
	void aBlankAppDataIsNoWindows() {
		assertEquals(LINUX_DEFAULT, CloudFolders.nextcloudConfigFiles(Map.of("APPDATA", " ")::get, "/home/x"));
	}

	@Test
	void withoutHomeOnlyWhatTheEnvironmentNames() {
		assertEquals(List.of(), CloudFolders.nextcloudConfigFiles(name -> null, null));
		assertEquals(List.of(cfgIn("/daten/cfg")),
				CloudFolders.nextcloudConfigFiles(Map.of("XDG_CONFIG_HOME", "/daten/cfg")::get, " "));
	}

	@Test
	void everyExistingConfigurationIsRead(@TempDir Path dir) throws IOException {
		Path missing = dir.resolve("fehlt.cfg");
		Path first = Files.writeString(dir.resolve("eins.cfg"), "eins");
		Path second = Files.writeString(dir.resolve("zwei.cfg"), "zwei");

		assertEquals(List.of("eins", "zwei"), CloudFolders.readExisting(List.of(first, missing, second)));
		assertEquals(List.of(), CloudFolders.readExisting(List.of(missing)));
	}

	@Test
	void anUnreadableConfigurationDoesNotTakeTheOthersAlong(@TempDir Path dir) throws IOException {
		Path unreadable = Files.createDirectory(dir.resolve("ordner.cfg"));
		Path readable = Files.writeString(dir.resolve("eins.cfg"), "eins");

		assertEquals(List.of("eins"), CloudFolders.readExisting(List.of(unreadable, readable)));
		assertThrows(UncheckedIOException.class, () -> CloudFolders.readExisting(List.of(unreadable)),
				"ist keine lesbar, zählt der Fehler wie bisher");
	}

	@Test
	void foldersOfAllConfigurationsCount() {
		// nach dem Wechsel vom normalen Client zum Flatpak bleibt die alte Datei liegen; beide Konten haben den Index 0
		String old = "[Accounts]\n0\\url=https://alt.example.de\n0\\Folders\\1\\localPath=/home/x/Alt/\n";
		String current = "[Accounts]\n0\\url=https://cloud.example.de\n0\\Folders\\1\\localPath=/home/x/Nextcloud/\n";
		CloudFolders folders = new CloudFolders(() -> List.of(old, current), name -> null);

		assertEquals(Optional.of(EXAMPLE), folders.find("/home/x/Nextcloud/Karten"));
		assertEquals(Optional.of(new CloudTarget("alt.example.de", 443)), folders.find("/home/x/Alt"));
	}

	@Test
	void aNextcloudFolderWithALinuxPathNamesItsServer() {
		String cfg = "[Accounts]\n0\\url=https://cloud.example.de\n0\\Folders\\1\\localPath=/home/x/Nextcloud/\n";
		CloudFolders folders = withCfg(cfg);

		assertEquals(Optional.of(EXAMPLE), folders.find("/home/x/Nextcloud/Kartenvorverkauf"));
		assertEquals(Optional.empty(), folders.find("/home/x/Dokumente"));
	}

	@Test
	void configurationIsReadOnEveryLookup() {
		String[] cfg = { "" };
		CloudFolders folders = new CloudFolders(() -> List.of(cfg[0]), name -> null);

		assertTrue(folders.find("D:\\ECV").isEmpty());
		cfg[0] = CFG;
		assertEquals(Optional.of(EXAMPLE), folders.find("D:\\ECV"));
	}
}
