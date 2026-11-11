package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.update.AppVersion;
import de.eltviller_carneval_verein.karten.update.FolderOffer;
import de.eltviller_carneval_verein.karten.update.FolderState;
import de.eltviller_carneval_verein.karten.update.Grund;
import de.eltviller_carneval_verein.karten.update.Installation;
import de.eltviller_carneval_verein.karten.update.StationEntry;
import de.eltviller_carneval_verein.karten.update.UpdateInfo;
import de.eltviller_carneval_verein.karten.update.UpdateState;
import de.eltviller_carneval_verein.karten.ui.shared.UpdateHint.Action;

class UpdateHintTest {

	private static final String FAMILY = "EcvKarten_0cs1gtds5d9x0";
	private static final Instant T = Instant.parse("2026-11-10T13:00:00Z");
	private static final AppVersion V111 = new AppVersion("1.1.1", 0);
	private static final AppVersion V112 = new AppVersion("1.1.2", 0);
	private static final UpdateState.Available AVAILABLE = new UpdateState.Available(new UpdateInfo(V112, FAMILY, false), T);
	private static final UpdateState.Available OTHER_FAMILY = new UpdateState.Available(new UpdateInfo(V112, "Andere_x", false), T);

	private static Optional<Installation> inst(String os, boolean packaged) {
		Map<String, String> props = Map.of("app.version", "1.1.0", "app.revision", "0", "app.fsname", "ecv-karten",
				"app.repositoryUrl", "http://localhost/upd", "os.name", os, "app.windows.userModelID", FAMILY + "!App");
		Map<String, String> env = packaged ? Map.of("PACKAGE_LOCALAPPDATA", "C:\\x\\Packages\\" + FAMILY) : Map.of();
		return Installation.from(props::get, env::get);
	}

	private static final Optional<Installation> WIN = inst("Windows 11", true);
	private static final Optional<Installation> LINUX = inst("Linux", false);

	private static FolderOffer offer(AppVersion v) {
		return new FolderOffer() {
			@Override
			public Path file() {
				return Path.of("a.msix");
			}

			@Override
			public AppVersion version() {
				return v;
			}

			@Override
			public Instant firstSeen() {
				return T;
			}

			@Override
			public boolean stillValid() {
				return true;
			}
		};
	}

	private static FolderState folder(Optional<FolderOffer> offer, Optional<AppVersion> reinstall,
			Optional<StationEntry> newer) {
		return new FolderState(List.of(), newer, offer, Optional.empty(), reinstall, Optional.empty());
	}

	private static final Optional<StationEntry> NEWER = Optional
			.of(new StationEntry("bbbb", "Kasse 2", "ecv-karten", V111, "WINDOWS_PAKET", T));

	@Test
	void folderBeatsGithub() {
		UpdateHint.Hint h = UpdateHint.choose(AVAILABLE, folder(Optional.of(offer(V111)), Optional.empty(), NEWER), WIN).orElseThrow();
		assertEquals(Action.UPDATE_FOLDER, h.action());
		assertEquals(UpdateTexts.folderIndicatorText(V111), h.text());
		assertEquals(UpdateTexts.FOLDER_TOOLTIP, h.tooltip());
		assertEquals(V111, h.folder().orElseThrow().version());
	}

	@Test
	void githubBeatsReinstall() {
		UpdateHint.Hint h = UpdateHint.choose(AVAILABLE, folder(Optional.empty(), Optional.of(V112), NEWER), WIN).orElseThrow();
		assertEquals(Action.UPDATE_GITHUB, h.action());
		assertEquals(UpdateTexts.indicatorText(V112), h.text());
		assertEquals(AVAILABLE, h.github().orElseThrow());
	}

	@Test
	void githubNotInPlaceShowsDetails() {
		UpdateHint.Hint h = UpdateHint.choose(OTHER_FAMILY, FolderState.empty(), WIN).orElseThrow();
		assertEquals(Action.OPEN_SETTINGS, h.action());
		assertEquals(UpdateTexts.INDICATOR_TOOLTIP_DETAILS, h.tooltip());
	}

	@Test
	void reinstallBeatsNewerStation() {
		UpdateHint.Hint h = UpdateHint.choose(new UpdateState.UpToDate(T), folder(Optional.empty(), Optional.of(V112), NEWER), WIN)
				.orElseThrow();
		assertEquals(Action.OPEN_SETTINGS, h.action());
		assertEquals(UpdateTexts.indicatorText(V112), h.text());
		assertEquals(UpdateTexts.INDICATOR_TOOLTIP_DETAILS, h.tooltip());
	}

	@Test
	void newerStationBeatsCheckImpossible() {
		UpdateState cert = new UpdateState.CheckFailed(Grund.CERTIFICATE, T, Optional.empty());
		UpdateHint.Hint h = UpdateHint.choose(cert, folder(Optional.empty(), Optional.empty(), NEWER), WIN).orElseThrow();
		assertEquals(UpdateTexts.newerStationText("Kasse 2", V111), h.text());
		assertEquals(UpdateTexts.NEWER_STATION_TOOLTIP, h.tooltip());
	}

	@Test
	void newerStationOnLinuxOpensSettings() {
		UpdateHint.Hint h = UpdateHint.choose(new UpdateState.Unsupported(), folder(Optional.empty(), Optional.empty(), NEWER),
				LINUX).orElseThrow();
		assertEquals(Action.OPEN_SETTINGS, h.action());
	}

	@Test
	void checkImpossibleStillShown() {
		UpdateState cert = new UpdateState.CheckFailed(Grund.CERTIFICATE, T, Optional.empty());
		assertEquals(UpdateTexts.CHECK_IMPOSSIBLE, UpdateHint.choose(cert, FolderState.empty(), WIN).orElseThrow().text());
	}

	@Test
	void nothingGivesEmpty() {
		assertEquals(Optional.empty(), UpdateHint.choose(new UpdateState.UpToDate(T), FolderState.empty(), WIN));
	}
}
