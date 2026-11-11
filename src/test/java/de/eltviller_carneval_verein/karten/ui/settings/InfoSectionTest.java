package de.eltviller_carneval_verein.karten.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.ui.shared.UpdateTexts;
import de.eltviller_carneval_verein.karten.update.AppVersion;
import de.eltviller_carneval_verein.karten.update.Grund;
import de.eltviller_carneval_verein.karten.update.Installation;
import de.eltviller_carneval_verein.karten.update.UpdateInfo;
import de.eltviller_carneval_verein.karten.update.UpdateState;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

class InfoSectionTest {

	private static final String FAMILY = "EcvKarten_0cs1gtds5d9x0";
	private static final Instant T = Instant.parse("2026-11-07T13:00:00Z");
	private static final Optional<AppVersion> INSTALLED = AppVersion.parse("1.1.0", "1");

	private final List<String> calls = new ArrayList<>();
	private final SimpleObjectProperty<UpdateState> state = new SimpleObjectProperty<>(new UpdateState.NotChecked());
	/** Was {@code releasePage} der Aktionen liefert (KARV-125). */
	private Optional<URI> page = Optional.empty();
	private boolean openFails;

	@BeforeAll
	static void startFx() {
		try {
			Platform.startup(() -> {
			});
		} catch (IllegalStateException alreadyStarted) {
			// der Toolkit läuft schon (anderer Test im selben Lauf)
		}
		Platform.setImplicitExit(false);
	}

	private static <T> T onFx(Supplier<T> action) throws Exception {
		CompletableFuture<T> result = new CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				result.complete(action.get());
			} catch (Throwable e) {
				result.completeExceptionally(e);
			}
		});
		return result.get(10, TimeUnit.SECONDS);
	}

	private static Optional<Installation> installation(String os, boolean packaged) {
		Map<String, String> props = Map.of("app.version", "1.1.0", "app.revision", "1", "app.fsname", "ecv-karten",
				"app.repositoryUrl", "http://localhost:8765/upd", "os.name", os, "app.windows.userModelID",
				FAMILY + "!EcvKarten");
		Map<String, String> env = packaged ? Map.of("PACKAGE_LOCALAPPDATA", "C:\\x\\Packages\\" + FAMILY) : Map.of();
		return Installation.from(props::get, env::get);
	}

	private static UpdateState.Available available(String family) {
		return new UpdateState.Available(new UpdateInfo(AppVersion.parse("1.1.0", "2").orElseThrow(), family, false), T);
	}

	private SettingsSection build(Optional<AppVersion> version, Optional<Installation> inst) throws Exception {
		PatchNoteActions actions = new PatchNoteActions(v -> page, (p, onError) -> {
			calls.add("open " + p);
			if (openFails) {
				onError.accept("Die Seite ließ sich nicht öffnen: " + p);
			}
		}, () -> calls.add("patchnotes"), text -> calls.add("error " + text));
		return onFx(() -> InfoSection.build(version, inst, state, () -> calls.add("check"),
				a -> calls.add("update " + a.neu().version().display()), () -> calls.add("browser"), actions));
	}

	private SettingsSection windowsPackage() throws Exception {
		return build(INSTALLED, installation("Windows 11", true));
	}

	private static Node find(SettingsSection section, String id) {
		for (SettingRow row : section.rows()) {
			Node found = row.control().lookup("#" + id);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static boolean shown(SettingsSection section, String id) {
		Node node = find(section, id);
		return node != null && node.isVisible() && node.isManaged();
	}

	private static String text(SettingsSection section, String id) {
		return ((Label) find(section, id)).getText();
	}

	private void set(UpdateState next) throws Exception {
		onFx(() -> {
			state.set(next);
			return null;
		});
	}

	@Test
	void rowsAndTitle() throws Exception {
		SettingsSection s = windowsPackage();
		assertEquals(UpdateTexts.SECTION_TITLE, s.title());
		assertEquals(List.of(UpdateTexts.ROW_VERSION, UpdateTexts.PATCH_NOTES_ROW, UpdateTexts.ROW_UPDATES,
				UpdateTexts.ROW_UPDATE_FILE), s.rows().stream().map(SettingRow::label).toList());
		assertEquals("1.1.0 (Rev. 1)", text(s, "update-version"));
		assertNull(s.rows().get(2).hint());
	}

	@Test
	void windowsPackageAvailableShowsBothButtons() throws Exception {
		state.set(available(FAMILY));
		SettingsSection s = windowsPackage();
		assertTrue(shown(s, "update-check-now"));
		assertTrue(shown(s, "update-now"));
		assertTrue(shown(s, "update-browser"));
		assertTrue(find(s, "update-browser").getStyleClass().contains("button-default"));
		assertFalse(shown(s, "update-platform"));
		onFx(() -> {
			((Button) find(s, "update-now")).fire();
			((Button) find(s, "update-browser")).fire();
			((Button) find(s, "update-check-now")).fire();
			return null;
		});
		assertEquals(List.of("update 1.1.0 (Rev. 2)", "browser", "check"), calls);
	}

	@Test
	void upToDateHidesBrowserButton() throws Exception {
		state.set(new UpdateState.UpToDate(T));
		SettingsSection s = windowsPackage();
		assertFalse(shown(s, "update-browser"));
		assertFalse(shown(s, "update-now"));
		assertTrue(shown(s, "update-check-now"));
	}

	@Test
	void notCheckedAndFailedShowBrowserButton() throws Exception {
		SettingsSection s = windowsPackage();
		assertTrue(shown(s, "update-browser"));
		set(new UpdateState.CheckFailed(Grund.CERTIFICATE, T, Optional.empty()));
		assertTrue(shown(s, "update-browser"));
	}

	@Test
	void checkingDisablesCheckNowAndKeepsUpdateButton() throws Exception {
		state.set(new UpdateState.Checking(available(FAMILY)));
		SettingsSection s = windowsPackage();
		assertTrue(find(s, "update-check-now").isDisabled());
		assertTrue(shown(s, "update-now"));
		assertEquals("Wird geprüft …", text(s, "update-status"));
	}

	@Test
	void checkingAfterUpToDateKeepsBrowserHidden() throws Exception {
		state.set(new UpdateState.Checking(new UpdateState.UpToDate(T)));
		assertFalse(shown(windowsPackage(), "update-browser"));
	}

	@Test
	void notInPlaceShowsReinstallTextWithoutBrowser() throws Exception {
		state.set(available("EcvKarten_neu"));
		SettingsSection s = windowsPackage();
		assertFalse(shown(s, "update-now"));
		assertFalse(shown(s, "update-browser"));
		assertTrue(shown(s, "update-platform"));
		assertEquals("Für diese Version ist eine Neuinstallation nötig.", text(s, "update-platform"));
	}

	@Test
	void linuxShowsTextAndCheckNowOnly() throws Exception {
		state.set(available(FAMILY));
		SettingsSection s = build(INSTALLED, installation("Linux", false));
		assertTrue(shown(s, "update-check-now"));
		assertFalse(shown(s, "update-now"));
		assertFalse(shown(s, "update-browser"));
		assertTrue(text(s, "update-platform").startsWith("Bitte so aktualisieren"));
		// Version, „Was ist neu“ (KARV-125), Updates; keine Update-Datei außerhalb des Windows-Pakets
		assertEquals(3, s.rows().size());
	}

	@Test
	void unsupportedShowsNoButtons() throws Exception {
		state.set(new UpdateState.Unsupported());
		SettingsSection s = build(Optional.empty(), Optional.empty());
		assertFalse(shown(s, "update-check-now"));
		assertFalse(shown(s, "update-now"));
		assertFalse(shown(s, "update-browser"));
		assertEquals("In der Entwicklungsversion nicht verfügbar", text(s, "update-status"));
	}

	@Test
	void versionRowDevelopment() throws Exception {
		state.set(new UpdateState.Unsupported());
		assertEquals(UpdateTexts.DEVELOPMENT_VERSION, text(build(Optional.empty(), Optional.empty()), "update-version"));
	}

	@Test
	void updateFileRowOnlyOnWindowsPackage() throws Exception {
		SettingsSection s = windowsPackage();
		TextField field = (TextField) find(s, "update-file");
		assertFalse(field.isEditable());
		assertEquals("http://localhost:8765/upd/ecv-karten.appinstaller", field.getText());
		assertEquals(UpdateTexts.UPDATE_FILE_HINT, s.rows().get(3).hint());
		assertNull(find(build(INSTALLED, installation("Windows 11", false)), "update-file"));
	}

	@Test
	void followsStateChanges() throws Exception {
		SettingsSection s = windowsPackage();
		assertEquals("Noch nicht geprüft", text(s, "update-status"));
		System.gc();
		set(available(FAMILY));
		assertTrue(shown(s, "update-now"));
		assertTrue(text(s, "update-status").startsWith("Version 1.1.0 (Rev. 2) verfügbar"));
	}

	private static final URI PAGE = URI.create("https://github.com/SirEddington/Kartenverwaltung/releases/tag/1.1.0");

	@Test
	void patchNotesRowAlwaysThere() throws Exception {
		for (SettingsSection s : List.of(windowsPackage(), build(INSTALLED, installation("Linux", false)),
				build(Optional.empty(), Optional.empty()))) {
			assertTrue(shown(s, "patch-notes-show"));
			assertEquals(UpdateTexts.PATCH_NOTES_BUTTON, ((Button) find(s, "patch-notes-show")).getText());
		}
		SettingsSection s = windowsPackage();
		onFx(() -> {
			((Button) find(s, "patch-notes-show")).fire();
			return null;
		});
		assertEquals(List.of("patchnotes"), calls);
	}

	@Test
	void linkOnlyWithKnownUpdateAndPage() throws Exception {
		assertFalse(shown(windowsPackage(), "update-patch-notes"));
		state.set(available(FAMILY));
		assertFalse(shown(windowsPackage(), "update-patch-notes"));
		page = Optional.of(PAGE);
		SettingsSection s = windowsPackage();
		assertTrue(shown(s, "update-patch-notes"));
		Hyperlink link = (Hyperlink) find(s, "update-patch-notes");
		assertEquals("Was ist neu in Version 1.1.0?", link.getText());
		assertEquals(PAGE, link.getUserData());
		assertEquals(PAGE.toString(), link.getTooltip().getText());
		assertFalse(shown(s, "update-patch-notes-address"));
	}

	@Test
	void linkKeepsItsTooltipAcrossChecks() throws Exception {
		page = Optional.of(PAGE);
		state.set(available(FAMILY));
		SettingsSection s = windowsPackage();
		Hyperlink link = (Hyperlink) find(s, "update-patch-notes");
		javafx.scene.control.Tooltip tooltip = link.getTooltip();
		set(new UpdateState.Checking(available(FAMILY)));
		set(available(FAMILY));
		assertTrue(tooltip == link.getTooltip());
		assertEquals(PAGE.toString(), link.getTooltip().getText());
	}

	@Test
	void linkFollowsState() throws Exception {
		page = Optional.of(PAGE);
		state.set(available(FAMILY));
		SettingsSection s = windowsPackage();
		assertTrue(shown(s, "update-patch-notes"));
		set(new UpdateState.UpToDate(T));
		assertFalse(shown(s, "update-patch-notes"));
	}

	@Test
	void linkFailureShowsFooterAndAddress() throws Exception {
		page = Optional.of(PAGE);
		openFails = true;
		state.set(available(FAMILY));
		SettingsSection s = build(INSTALLED, installation("Linux", false));
		onFx(() -> {
			((Hyperlink) find(s, "update-patch-notes")).fire();
			return null;
		});
		assertEquals(List.of("open " + PAGE, "error Die Seite ließ sich nicht öffnen: " + PAGE), calls);
		assertTrue(shown(s, "update-patch-notes-address"));
		TextField address = (TextField) find(s, "update-patch-notes-address");
		assertFalse(address.isEditable());
		assertEquals(PAGE.toString(), address.getText());
	}
}
