package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import de.eltviller_carneval_verein.karten.update.AppVersion;
import de.eltviller_carneval_verein.karten.update.Grund;
import de.eltviller_carneval_verein.karten.update.Installation;
import de.eltviller_carneval_verein.karten.update.UpdateInfo;
import de.eltviller_carneval_verein.karten.update.UpdateState;
import javafx.application.Platform;

class UpdateIndicatorTest {

	private static final String FAMILY = "EcvKarten_0cs1gtds5d9x0";
	private static final Instant T = Instant.parse("2026-11-07T13:00:00Z");
	private static final UpdateState.Available AVAILABLE = new UpdateState.Available(
			new UpdateInfo(AppVersion.parse("1.1.0", "2").orElseThrow(), FAMILY, false), T);

	private final List<String> calls = new ArrayList<>();

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

	private UpdateIndicator indicator(UpdateState state, Optional<Installation> inst) {
		return new UpdateIndicator(() -> state, () -> inst, a -> calls.add("update " + a.neu().version().display()),
				() -> calls.add("settings"));
	}

	@Test
	void hiddenWithoutUpdate() throws Exception {
		UpdateIndicator i = onFx(() -> indicator(new UpdateState.UpToDate(T), installation("Windows 11", true)));
		assertFalse(i.isVisible());
		assertFalse(i.isManaged());
	}

	@Test
	void showsVersionWhenAvailable() throws Exception {
		UpdateIndicator i = onFx(() -> indicator(AVAILABLE, installation("Windows 11", true)));
		assertTrue(i.isVisible());
		assertEquals("Update 1.1.0 (Rev. 2) verfügbar", i.getText());
		assertTrue(i.getStyleClass().contains("update-indicator"));
	}

	@Test
	void staysVisibleWhileChecking() throws Exception {
		UpdateIndicator i = onFx(() -> indicator(new UpdateState.Checking(AVAILABLE), installation("Windows 11", true)));
		assertTrue(i.isVisible());
	}

	@Test
	void checkImpossibleOnlyOnWindowsPackage() throws Exception {
		UpdateState cert = new UpdateState.CheckFailed(Grund.CERTIFICATE, T, Optional.empty());
		UpdateIndicator win = onFx(() -> indicator(cert, installation("Windows 11", true)));
		assertTrue(win.isVisible());
		assertEquals(UpdateTexts.CHECK_IMPOSSIBLE, win.getText());
		assertFalse(onFx(() -> indicator(cert, installation("Linux", false))).isVisible());
		UpdateState offline = new UpdateState.CheckFailed(Grund.NETWORK, T, Optional.empty());
		assertFalse(onFx(() -> indicator(offline, installation("Windows 11", true))).isVisible());
	}

	@Test
	void clickWithInPlaceRunsUpdate() throws Exception {
		onFx(() -> {
			indicator(AVAILABLE, installation("Windows 11", true)).fire();
			return null;
		});
		assertEquals(List.of("update 1.1.0 (Rev. 2)"), calls);
	}

	@Test
	void clickOtherwiseOpensSettings() throws Exception {
		onFx(() -> {
			indicator(AVAILABLE, installation("Linux", false)).fire();
			indicator(new UpdateState.CheckFailed(Grund.CERTIFICATE, T, Optional.empty()), installation("Windows 11", true))
					.fire();
			return null;
		});
		assertEquals(List.of("settings", "settings"), calls);
	}

	@Test
	void tooltipTexts() throws Exception {
		assertEquals(UpdateTexts.INDICATOR_TOOLTIP_UPDATE,
				onFx(() -> indicator(AVAILABLE, installation("Windows 11", true)).getTooltip().getText()));
		assertEquals(UpdateTexts.INDICATOR_TOOLTIP_DETAILS,
				onFx(() -> indicator(AVAILABLE, installation("Linux", false)).getTooltip().getText()));
		assertEquals(UpdateTexts.CHECK_IMPOSSIBLE_TOOLTIP, onFx(() -> indicator(
				new UpdateState.CheckFailed(Grund.PROXY, T, Optional.empty()), installation("Windows 11", true))
				.getTooltip().getText()));
	}
}
