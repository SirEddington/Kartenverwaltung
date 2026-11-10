package de.eltviller_carneval_verein.karten.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.settings.AppSettings;
import javafx.application.Platform;
import javafx.scene.control.CheckBox;

class DisplaySectionTest {

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

	@Test
	void oneRowForRestoringTheMenuAtStart() throws Exception {
		SettingsSection section = onFx(() -> DisplaySection.build(new AppSettings()));

		assertEquals("Anzeige", section.title());
		assertEquals(1, section.rows().size());
		SettingRow row = section.rows().get(0);
		assertEquals("Anzeige beim Start wiederherstellen", row.label());
		assertTrue(row.hint().contains("Saalansicht"));
		assertTrue(row.hint().contains("Hallen-Editor"));
		assertTrue(row.hint().contains("Kaufzusammenfassung"));
	}

	@Test
	void theCheckBoxShowsAndChangesTheSetting() throws Exception {
		AppSettings settings = new AppSettings();
		settings.getDisplay().setRestoreAtStart(false);

		boolean shownBefore = onFx(() -> {
			CheckBox box = (CheckBox) DisplaySection.build(settings).rows().get(0).control();
			boolean shown = box.isSelected();
			box.setSelected(true);
			return shown;
		});

		assertFalse(shownBefore);
		assertTrue(settings.getDisplay().isRestoreAtStart());
	}
}
