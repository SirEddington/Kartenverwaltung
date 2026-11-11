package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.update.AppVersion;
import de.eltviller_carneval_verein.karten.update.DesktopOps;
import de.eltviller_carneval_verein.karten.update.FolderOffer;
import de.eltviller_carneval_verein.karten.update.Grund;
import de.eltviller_carneval_verein.karten.update.Installation;
import de.eltviller_carneval_verein.karten.update.UpdateInfo;
import de.eltviller_carneval_verein.karten.update.UpdateSource;

class UpdateFlowTest {

	private static final String FAMILY = "EcvKarten_0cs1gtds5d9x0";
	private static final URI APPINSTALLER = URI.create("http://localhost:8765/upd/ecv-karten.appinstaller");
	private static final Path FILE = Path.of("ecv-update-ecv-karten-1.appinstaller");

	private final List<String> calls = new ArrayList<>();
	private boolean confirmAnswer = true;
	private boolean confirmCloseAnswer = true;
	/** Antwort ab dem zweiten confirmClose (nach Erfolg bei ersetzter Busy-Ansicht); null = wie das erste. */
	private Boolean laterConfirmCloseAnswer;
	private boolean sharedFolder;
	private boolean busyShown = true;
	private boolean connecting;
	private RuntimeException confirmCloseFailure;
	private RuntimeException showBusyFailure;
	private RuntimeException afterAbortFailure;
	private UpdateInfo fresh;
	private IOException downloadFailure;
	private Throwable openFailure;
	private IOException browseFailure;
	private String lastConfirmTitle;
	private String lastConfirmText;
	private String lastConfirmButton;
	private String lastConfirmCancel;
	private Optional<Confirmation.Link> lastConfirmLink;
	private Executor ui = Runnable::run;
	private Executor worker = Runnable::run;
	private boolean folderFileValid = true;
	private UpdateFlow flow;

	private static Installation installation(boolean packaged) {
		return installation(packaged, "http://localhost:8765/upd");
	}

	private static Installation installation(boolean packaged, String repositoryUrl) {
		Map<String, String> props = Map.of("app.version", "1.1.0", "app.revision", "1", "app.fsname", "ecv-karten",
				"app.repositoryUrl", repositoryUrl, "os.name", "Windows 11", "app.windows.userModelID", FAMILY + "!EcvKarten");
		Map<String, String> env = packaged ? Map.of("PACKAGE_LOCALAPPDATA", "C:\\Users\\u\\AppData\\Local\\Packages\\" + FAMILY)
				: Map.of();
		return Installation.from(props::get, env::get).orElseThrow();
	}

	private static UpdateInfo info(String revision, String family) {
		return new UpdateInfo(AppVersion.parse("1.1.0", revision).orElseThrow(), family, false);
	}

	private UpdateFlow flow(Installation inst) {
		UpdateSource source = new UpdateSource() {
			@Override
			public UpdateInfo fetchInfo() {
				calls.add("fetchInfo");
				return fresh;
			}

			@Override
			public Path downloadAppInstaller() throws IOException {
				calls.add("download");
				if (downloadFailure != null) {
					throw downloadFailure;
				}
				return FILE;
			}

			@Override
			public URI appInstallerUri() {
				return APPINSTALLER;
			}

			@Override
			public void cleanup() {
			}
		};
		DesktopOps desktop = new DesktopOps() {
			@Override
			public void open(Path file) throws IOException {
				calls.add("open " + file);
				if (openFailure instanceof IOException io) {
					throw io;
				}
				if (openFailure instanceof RuntimeException re) {
					throw re;
				}
			}

			@Override
			public void browse(URI uri) throws IOException {
				calls.add("browse " + uri);
				if (browseFailure != null) {
					throw browseFailure;
				}
			}
		};
		// Form aus KARV-132, dazu die Fünf-Argument-Methode mit dem Link (KARV-125)
		Confirmation confirmation = new Confirmation() {
			@Override
			public boolean ask(String title, String text, String button) {
				return ask(title, text, button, null);
			}

			@Override
			public boolean ask(String title, String text, String button, String cancel) {
				calls.add("confirm");
				lastConfirmTitle = title;
				lastConfirmText = text;
				lastConfirmButton = button;
				lastConfirmCancel = cancel;
				return confirmAnswer;
			}

			@Override
			public boolean ask(String title, String text, String button, String cancel, Optional<Confirmation.Link> link) {
				lastConfirmLink = link;
				return ask(title, text, button, cancel);
			}
		};
		return new UpdateFlow(inst, confirmation, () -> sharedFolder, () -> {
			calls.add("confirmClose");
			if (confirmCloseFailure != null) {
				throw confirmCloseFailure;
			}
			if (laterConfirmCloseAnswer != null && calls.stream().filter("confirmClose"::equals).count() > 1) {
				return laterConfirmCloseAnswer;
			}
			return confirmCloseAnswer;
		}, text -> {
			calls.add("busy " + text);
			if (showBusyFailure != null) {
				throw showBusyFailure;
			}
		}, source, desktop, () -> calls.add("exit"), () -> calls.add("menu"), () -> {
			calls.add("afterAbort");
			if (afterAbortFailure != null) {
				throw afterAbortFailure;
			}
		}, () -> calls.add("recheck"), text -> calls.add("error " + text), () -> busyShown, () -> connecting,
				ui, worker);
	}

	/** Wie {@link #installation(boolean)} im Paket, aber mit der echten Update-Quelle auf GitHub (KARV-125). */
	private static Installation githubInstallation() {
		return installation(true, "https://github.com/SirEddington/Kartenverwaltung/releases/latest/download");
	}

	/** Executoren, die in {@code calls} vermerken, auf welchem Weg eine Aufgabe lief. */
	private void recordThreads() {
		worker = r -> {
			calls.add("worker");
			r.run();
		};
		ui = r -> {
			calls.add("ui");
			r.run();
		};
	}

	private static AppVersion version(String version, String revision) {
		return AppVersion.parse(version, revision).orElseThrow();
	}

	@BeforeEach
	void setUp() {
		fresh = info("2", FAMILY);
		flow = flow(installation(true));
	}

	private static final Path MSIX = Path.of("updates", "ecv-karten-1.1.0-3.x64.msix");

	private FolderOffer folderOffer() {
		return folderOffer(AppVersion.parse("1.1.0", "3").orElseThrow());
	}

	private FolderOffer folderOffer(AppVersion v) {
		return new FolderOffer() {
			@Override
			public Path file() {
				return MSIX;
			}

			@Override
			public AppVersion version() {
				return v;
			}

			@Override
			public java.time.Instant firstSeen() {
				return java.time.Instant.EPOCH;
			}

			@Override
			public boolean stillValid() {
				calls.add("stillValid");
				return folderFileValid;
			}
		};
	}

	@Test
	void folderUpdateOpensFileAndExits() {
		flow.updateFromFolder(folderOffer(), false);
		assertEquals(List.of("confirm", "confirmClose", "busy " + UpdateTexts.BUSY, "stillValid", "open " + MSIX, "exit"),
				calls);
		assertEquals(UpdateTexts.CONFIRM_DIRECT_TITLE, lastConfirmTitle);
		assertEquals(UpdateTexts.confirmFolderText(AppVersion.parse("1.1.0", "3").orElseThrow()), lastConfirmText);
		assertEquals(UpdateTexts.BUTTON_UPDATE, lastConfirmButton);
	}

	/** KARV-125: Auch die Rückfrage zum Update aus dem Ordner verlinkt die Release-Seite, beim Start mit „Später“. */
	@Test
	void folderUpdateAsksWithLinkToHigherVersion() {
		flow = flow(githubInstallation());
		flow.updateFromFolder(folderOffer(AppVersion.parse("1.2.0", "0").orElseThrow()), true);
		Confirmation.Link link = lastConfirmLink.orElseThrow();
		assertEquals("Was ist neu in Version 1.2.0?", link.text());
		assertEquals(URI.create("https://github.com/SirEddington/Kartenverwaltung/releases/tag/1.2.0"), link.page());
		assertEquals(UpdateTexts.BUTTON_LATER, lastConfirmCancel);
	}

	@Test
	void folderUpdateWithOnlyNewerRevisionAsksWithoutLink() {
		flow = flow(githubInstallation());
		flow.updateFromFolder(folderOffer(), false);
		assertEquals(Optional.empty(), lastConfirmLink);
	}

	@Test
	void folderUpdateAtStartAsksOnceWithLater() {
		flow.updateFromFolder(folderOffer(), true);
		assertEquals(1, calls.stream().filter("confirm"::equals).count());
		assertEquals(UpdateTexts.START_TITLE, lastConfirmTitle);
		assertEquals(UpdateTexts.startText(AppVersion.parse("1.1.0", "3").orElseThrow()), lastConfirmText);
		assertEquals(UpdateTexts.BUTTON_LATER, lastConfirmCancel);
		assertTrue(calls.contains("exit"));
	}

	/** code-review: Eine hängende Freigabe hält die Busy-Ansicht nicht unbegrenzt fest. */
	@Test
	void hangingFolderCheckStopsWithError() {
		flow.setFolderCheckTimeout(java.time.Duration.ofMillis(200));
		FolderOffer hanging = new FolderOffer() {
			@Override
			public Path file() {
				return MSIX;
			}

			@Override
			public AppVersion version() {
				return AppVersion.parse("1.1.0", "3").orElseThrow();
			}

			@Override
			public java.time.Instant firstSeen() {
				return java.time.Instant.EPOCH;
			}

			@Override
			public boolean stillValid() {
				try {
					Thread.sleep(60_000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return true;
			}
		};
		long t0 = System.nanoTime();
		flow.updateFromFolder(hanging, false);
		assertTrue(System.nanoTime() - t0 < 10_000_000_000L, "kehrt nach dem Zeitlimit zurück");
		assertTrue(calls.contains("error " + UpdateTexts.ERROR_FOLDER_FILE), calls.toString());
		assertFalse(calls.contains("open " + MSIX));
	}

	@Test
	void folderFileChangedStopsWithError() {
		folderFileValid = false;
		flow.updateFromFolder(folderOffer(), false);
		assertEquals(List.of("confirm", "confirmClose", "busy " + UpdateTexts.BUSY, "stillValid", "menu",
				"error " + UpdateTexts.ERROR_FOLDER_FILE, "recheck", "afterAbort"), calls);
		assertFalse(flow.isRunning());
	}

	@Test
	void folderUpdateBlockedWhileConnecting() {
		connecting = true;
		flow.updateFromFolder(folderOffer(), true);
		assertEquals(List.of(), calls);
	}

	@Test
	void laterAtStartDoesNothing() {
		confirmAnswer = false;
		flow.updateFromFolder(folderOffer(), true);
		assertEquals(List.of("confirm", "afterAbort"), calls);
	}

	@Test
	void directConfirmMentionsRunningDownload() {
		flow.setFolderDownloadRunning(() -> true);
		flow.update(info("2", FAMILY));
		assertTrue(lastConfirmText.endsWith(UpdateTexts.DOWNLOAD_RUNNING_NOTE), lastConfirmText);
	}

	@Test
	void directConfirmWithoutDownloadHasNoNote() {
		flow.update(info("2", FAMILY));
		assertFalse(lastConfirmText.contains(UpdateTexts.DOWNLOAD_RUNNING_NOTE));
	}

	@Test
	void noAtConfirmDoesNothing() {
		confirmAnswer = false;
		flow.update(info("2", FAMILY));
		assertEquals(List.of("confirm", "afterAbort"), calls);
		assertFalse(flow.isRunning());
	}

	@Test
	void noAtConfirmCloseDoesNothing() {
		confirmCloseAnswer = false;
		flow.update(info("2", FAMILY));
		assertEquals(List.of("confirm", "confirmClose", "afterAbort"), calls);
		assertFalse(flow.isRunning());
	}

	@Test
	void successOpensAndExits() {
		flow.update(info("2", FAMILY));
		assertEquals(List.of("confirm", "confirmClose", "busy " + UpdateTexts.BUSY, "fetchInfo", "download", "open " + FILE,
				"exit"), calls);
		assertTrue(flow.isRunning());
	}

	@Test
	void freshInfoNotNewerStopsAndRechecks() {
		fresh = info("1", FAMILY);
		flow.update(info("2", FAMILY));
		assertEquals(List.of("confirm", "confirmClose", "busy " + UpdateTexts.BUSY, "fetchInfo", "menu",
				"error " + UpdateTexts.ERROR_NO_NEWER, "recheck", "afterAbort"), calls);
		assertFalse(flow.isRunning());
	}

	@Test
	void freshInfoWithOtherFamilyStopsAndRechecks() {
		fresh = info("3", "EcvKarten_neu");
		flow.update(info("2", FAMILY));
		assertTrue(calls.contains("error " + UpdateTexts.ERROR_REINSTALL), calls.toString());
		assertTrue(calls.contains("recheck"));
		assertFalse(calls.contains("download"));
	}

	@Test
	void downloadFailureGoesBackToMenu() {
		downloadFailure = new UnknownHostException("github.com");
		flow.update(info("2", FAMILY));
		assertEquals(List.of("confirm", "confirmClose", "busy " + UpdateTexts.BUSY, "fetchInfo", "download", "menu",
				"error " + UpdateTexts.errorDirect(Grund.NETWORK), "afterAbort"), calls);
		assertFalse(flow.isRunning());
	}

	@Test
	void openRuntimeExceptionGoesBackToMenu() {
		openFailure = new IllegalStateException("kaputt");
		flow.update(info("2", FAMILY));
		assertTrue(calls.contains("menu"));
		assertTrue(calls.contains("error " + UpdateTexts.errorDirect(Grund.UNKNOWN)), calls.toString());
		assertFalse(calls.contains("exit"));
		assertFalse(flow.isRunning());
	}

	@Test
	void browserPathBrowsesWithoutFetchAndExits() {
		flow.updateViaBrowser();
		assertEquals(List.of("confirm", "confirmClose", "busy " + UpdateTexts.BUSY, "browse " + APPINSTALLER, "exit"), calls);
	}

	@Test
	void browserFailureShowsBrowserError() {
		browseFailure = new IOException("kein Browser");
		flow.updateViaBrowser();
		assertEquals(List.of("confirm", "confirmClose", "busy " + UpdateTexts.BUSY, "browse " + APPINSTALLER, "menu",
				"error " + UpdateTexts.ERROR_BROWSER, "afterAbort"), calls);
	}

	@Test
	void secondCallWhileRunningIsIgnored() {
		flow.update(info("2", FAMILY));
		calls.clear();
		flow.update(info("2", FAMILY));
		flow.updateViaBrowser();
		assertEquals(List.of(), calls);
	}

	@Test
	void ignoredWhileConnecting() {
		connecting = true;
		flow.update(info("2", FAMILY));
		flow.updateViaBrowser();
		assertEquals(List.of(), calls);
		assertFalse(flow.isRunning());
	}

	@Test
	void confirmCloseThrowsReleasesRunning() {
		confirmCloseFailure = new IllegalStateException("Saver kaputt");
		flow.update(info("2", FAMILY));
		assertFalse(flow.isRunning());
		assertTrue(calls.contains("afterAbort"));
		assertFalse(calls.contains("menu"));
	}

	@Test
	void showBusyThrowsAfterConfirmCloseGoesBackToMenu() {
		showBusyFailure = new IllegalStateException("Ansicht kaputt");
		flow.update(info("2", FAMILY));
		assertTrue(calls.contains("menu"), calls.toString());
		assertTrue(calls.contains("afterAbort"));
		assertFalse(flow.isRunning());
	}

	@Test
	void windowClosedDuringWorkerShowsNothing() {
		busyShown = false;
		downloadFailure = new IOException("weg");
		flow.update(info("2", FAMILY));
		assertFalse(calls.contains("menu"));
		assertFalse(calls.stream().anyMatch(c -> c.startsWith("error")));
		assertFalse(flow.isRunning());
	}

	@Test
	void successWithReplacedViewAsksAgainBeforeExit() {
		busyShown = false;
		flow.update(info("2", FAMILY));
		assertEquals(List.of("confirm", "confirmClose", "busy " + UpdateTexts.BUSY, "fetchInfo", "download", "open " + FILE,
				"confirmClose", "exit"), calls);
	}

	@Test
	void successWithReplacedViewAndCancelDoesNotExit() {
		busyShown = false;
		laterConfirmCloseAnswer = false;
		flow.update(info("2", FAMILY));
		assertFalse(calls.contains("exit"), calls.toString());
		assertTrue(calls.contains("error " + UpdateTexts.INSTALLER_OPEN), calls.toString());
		assertTrue(calls.contains("afterAbort"));
		assertFalse(flow.isRunning());
	}

	@Test
	void browserSuccessWithReplacedViewAsksAgainBeforeExit() {
		busyShown = false;
		laterConfirmCloseAnswer = false;
		flow.updateViaBrowser();
		assertFalse(calls.contains("exit"), calls.toString());
		assertTrue(calls.contains("error " + UpdateTexts.INSTALLER_OPEN), calls.toString());
	}

	@Test
	void afterAbortExceptionIsSwallowed() {
		confirmAnswer = false;
		afterAbortFailure = new IllegalStateException("Konflikte kaputt");
		flow.update(info("2", FAMILY));
		assertFalse(flow.isRunning());
	}

	@Test
	void buttonLabels() {
		confirmAnswer = false;
		flow.update(info("2", FAMILY));
		assertEquals(UpdateTexts.BUTTON_UPDATE, lastConfirmButton);
		flow.updateViaBrowser();
		assertEquals(UpdateTexts.BUTTON_BROWSER, lastConfirmButton);
	}

	@Test
	void sharedFolderSentenceOnlyWhenConfigured() {
		confirmAnswer = false;
		flow.update(info("2", FAMILY));
		assertEquals(UpdateTexts.confirmDirectText(info("2", FAMILY).version(), false), lastConfirmText);
		sharedFolder = true;
		flow.update(info("2", FAMILY));
		assertEquals(UpdateTexts.confirmDirectText(info("2", FAMILY).version(), true), lastConfirmText);
	}

	@Test
	void updateRequiresCanUpdateInPlace() {
		flow = flow(installation(false));
		flow.update(info("2", FAMILY));
		assertEquals(List.of(), calls);
	}

	private static final URI PAGE_120 = URI.create("https://github.com/SirEddington/Kartenverwaltung/releases/tag/1.2.0");

	@Test
	void releasePageOnlyForHigherVersion() {
		flow = flow(githubInstallation());
		assertEquals(Optional.of(PAGE_120), flow.releasePage(version("1.2.0", null)));
		assertEquals(Optional.empty(), flow.releasePage(version("1.1.0", "2")));
		assertEquals(Optional.empty(), flow.releasePage(version("1.0.9", null)));
		assertEquals(Optional.empty(), flow(installation(true)).releasePage(version("1.2.0", null)));
	}

	@Test
	void updateAsksWithLink() {
		flow = flow(githubInstallation());
		flow.update(new UpdateInfo(version("1.2.0", null), FAMILY, false));
		Confirmation.Link link = lastConfirmLink.orElseThrow();
		assertEquals("Was ist neu in Version 1.2.0?", link.text());
		assertEquals(PAGE_120, link.page());
	}

	@Test
	void updateWithOnlyNewerRevisionAsksWithoutLink() {
		flow = flow(githubInstallation());
		flow.update(info("2", FAMILY));
		assertEquals(Optional.empty(), lastConfirmLink);
	}

	@Test
	void updateViaBrowserAsksWithoutLink() {
		flow = flow(githubInstallation());
		flow.updateViaBrowser();
		assertEquals(Optional.empty(), lastConfirmLink);
	}

	@Test
	void openReleasePageBrowsesOnWorker() {
		recordThreads();
		flow = flow(githubInstallation());
		List<String> errors = new ArrayList<>();
		flow.openReleasePage(PAGE_120, errors::add);
		assertEquals(List.of("worker", "browse " + PAGE_120), calls);
		assertEquals(List.of(), errors);
	}

	@Test
	void openReleasePageReportsFailureOnUi() {
		recordThreads();
		browseFailure = new IOException("kein Browser");
		flow = flow(githubInstallation());
		List<String> errors = new ArrayList<>();
		flow.openReleasePage(PAGE_120, text -> {
			calls.add("onError");
			errors.add(text);
		});
		assertEquals(List.of("worker", "browse " + PAGE_120, "ui", "onError"), calls);
		assertEquals(List.of("Die Seite ließ sich nicht öffnen: " + PAGE_120), errors);
	}

	@Test
	void linkOpensThroughTheFlow() {
		flow = flow(githubInstallation());
		flow.update(new UpdateInfo(version("1.2.0", null), FAMILY, false));
		calls.clear();
		Confirmation.Link link = lastConfirmLink.orElseThrow();
		link.open().accept(link.page(), text -> calls.add("onError"));
		assertEquals(List.of("browse " + PAGE_120), calls);
	}
}
