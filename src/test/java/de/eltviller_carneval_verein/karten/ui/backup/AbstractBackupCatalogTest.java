package de.eltviller_carneval_verein.karten.ui.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.LockedUnitException;
import de.eltviller_carneval_verein.karten.repository.Restored;
import de.eltviller_carneval_verein.karten.repository.SavePoint;
import de.eltviller_carneval_verein.karten.ui.backup.AbstractBackupCatalog.Restorer;
import de.eltviller_carneval_verein.karten.ui.backup.BackupCatalog.Entry;

/** Das Gemeinsame der Kataloge mit Ersatz-Wiederherstellern, die die Fehlerfälle erzeugen. */
class AbstractBackupCatalogTest {

	private static final SavePoint POINT = new SavePoint("s1", Instant.parse("2026-10-27T10:00:00Z"), "Kasse1", "Anna", 1);
	private static final Entry ENTRY = new Entry("h1", "Stadthalle", false);

	private static AbstractBackupCatalog<Hall> catalog(Restorer<Hall> restorer) {
		return new AbstractBackupCatalog<>("Halle", List::of, id -> List.of(), restorer, Hall::getName) {
			@Override
			public String restoreWarning() {
				return "Warnung.";
			}
		};
	}

	@Test
	void ioFailureBecomesFailureResultWithMessage() {
		RestoreResult result = catalog((id, point, adjust) -> {
			throw new IOException("Platte voll");
		}).restore(ENTRY, POINT);

		assertFalse(result.success());
		assertEquals("Der Speicherstand konnte nicht wiederhergestellt werden: Platte voll", result.message());
	}

	@Test
	void otherRuntimeFailureBecomesFailureResultWithMessage() {
		RestoreResult result = catalog((id, point, adjust) -> {
			throw new IllegalStateException("Datei gesperrt");
		}).restore(ENTRY, POINT);

		assertFalse(result.success());
		assertEquals("Der Speicherstand konnte nicht wiederhergestellt werden: Datei gesperrt", result.message());
	}

	@Test
	void lockedUnitKeepsItsOwnText() {
		RestoreResult result = catalog((id, point, adjust) -> {
			throw new LockedUnitException("Die Halle ist gesperrt.");
		}).restore(ENTRY, POINT);

		assertFalse(result.success());
		assertEquals("Die Halle ist gesperrt.", result.message());
	}

	@Test
	void restorerReceivesIdPointAndTheCatalogsAdjustment() {
		Hall restored = new Hall();
		restored.setName("Stadthalle");
		RestoreResult result = catalog((id, point, adjust) -> {
			assertEquals("h1", id);
			assertSame(POINT, point);
			return new Restored<>(restored, adjust.apply(restored));
		}).restore(ENTRY, POINT);

		assertTrue(result.success());
		assertTrue(result.notes().isEmpty());
	}

	@Test
	void savePointsOfTheCatalogIoFailureIsWrapped() {
		Function<String, List<SavePoint>> wrapped = AbstractBackupCatalog.unchecked(id -> {
			throw new IOException("nicht lesbar");
		});

		UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> wrapped.apply("h1"));
		assertEquals("nicht lesbar", thrown.getCause().getMessage());
	}

	@Test
	void versionsPassThroughTheSavePointFunction() {
		AbstractBackupCatalog<Hall> catalog = new AbstractBackupCatalog<>("Halle", List::of, id -> List.of(POINT), (id, p, adjust) -> null,
				Hall::getName) {
			@Override
			public String restoreWarning() {
				return "";
			}
		};

		assertEquals(List.of(POINT), catalog.versions("h1"));
	}
}
