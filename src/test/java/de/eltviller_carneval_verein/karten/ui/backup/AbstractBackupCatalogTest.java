package de.eltviller_carneval_verein.karten.ui.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.LockedUnitException;
import de.eltviller_carneval_verein.karten.repository.RestorePlan;
import de.eltviller_carneval_verein.karten.repository.Restored;
import de.eltviller_carneval_verein.karten.repository.SavePoint;
import de.eltviller_carneval_verein.karten.ui.backup.AbstractBackupCatalog.Committer;
import de.eltviller_carneval_verein.karten.ui.backup.AbstractBackupCatalog.Preparer;
import de.eltviller_carneval_verein.karten.ui.backup.BackupCatalog.Entry;
import de.eltviller_carneval_verein.karten.ui.backup.BackupCatalog.Prepared;

/** Das Gemeinsame der Kataloge mit Ersatz-Wiederherstellern, die die Fehlerfälle erzeugen. */
class AbstractBackupCatalogTest {

	private static final SavePoint POINT = new SavePoint("s1", Instant.parse("2026-10-27T10:00:00Z"), "Kasse1", "Anna", 1);
	private static final Entry ENTRY = new Entry("h1", "Stadthalle", false);

	private static Hall hall(String name) {
		Hall hall = new Hall();
		hall.setName(name);
		return hall;
	}

	private static RestorePlan<Hall> plan(Hall target) {
		return new RestorePlan<>("h1", List.of(), target, 0);
	}

	private static AbstractBackupCatalog<Hall> catalog(Preparer<Hall> preparer, Committer<Hall> committer) {
		return new AbstractBackupCatalog<>("Halle", List::of, id -> List.of(), preparer, committer, Hall::getName) {
			@Override
			public String restoreWarning() {
				return "Warnung.";
			}
		};
	}

	/** Ein Katalog, dessen Vorbereitung gelingt und dessen Übernahme {@code failure} wirft. */
	private static AbstractBackupCatalog<Hall> catalogFailingOnCommit(RuntimeException failure) {
		return catalog((id, point) -> plan(hall("Stadthalle")), (plan, adjust) -> {
			throw failure;
		});
	}

	@Test
	void ioFailureOfPrepareBecomesFailureResultWithMessage() {
		RestoreResult result = catalog((id, point) -> {
			throw new IOException("Platte voll");
		}, (plan, adjust) -> null).restore(ENTRY, POINT);

		assertFalse(result.success());
		assertEquals("Der Speicherstand konnte nicht wiederhergestellt werden: Platte voll", result.message());
	}

	@Test
	void otherRuntimeFailureOfPrepareBecomesFailureResultWithMessage() {
		RestoreResult result = catalog((id, point) -> {
			throw new IllegalStateException("Datei gesperrt");
		}, (plan, adjust) -> null).restore(ENTRY, POINT);

		assertFalse(result.success());
		assertEquals("Der Speicherstand konnte nicht wiederhergestellt werden: Datei gesperrt", result.message());
	}

	@Test
	void lockedUnitOfPrepareKeepsItsOwnText() {
		RestoreResult result = catalog((id, point) -> {
			throw new LockedUnitException("Die Halle ist gesperrt.");
		}, (plan, adjust) -> null).restore(ENTRY, POINT);

		assertFalse(result.success());
		assertEquals("Die Halle ist gesperrt.", result.message());
	}

	@Test
	void lockedUnitOfCommitKeepsItsOwnText() {
		RestoreResult result = catalogFailingOnCommit(new LockedUnitException("Die Halle wurde inzwischen geändert."))
				.restore(ENTRY, POINT);

		assertFalse(result.success());
		assertEquals("Die Halle wurde inzwischen geändert.", result.message());
	}

	@Test
	void runtimeFailureOfCommitBecomesFailureResultWithMessage() {
		RestoreResult result = catalogFailingOnCommit(new IllegalStateException("Platte voll")).restore(ENTRY, POINT);

		assertFalse(result.success());
		assertEquals("Der Speicherstand konnte nicht wiederhergestellt werden: Platte voll", result.message());
	}

	@Test
	void prepareDoesNotCommitAndFailureWaitsForCommit() {
		List<String> calls = new ArrayList<>();
		AbstractBackupCatalog<Hall> catalog = catalog((id, point) -> {
			calls.add("prepare");
			throw new LockedUnitException("gesperrt");
		}, (plan, adjust) -> {
			calls.add("commit");
			return null;
		});

		Prepared prepared = catalog.prepare(ENTRY, POINT);

		assertEquals(List.of("prepare"), calls);
		RestoreResult result = catalog.commit(prepared);
		assertEquals(List.of("prepare"), calls);
		assertFalse(result.success());
		assertEquals("gesperrt", result.message());
	}

	@Test
	void commitReceivesThePlanOfPrepareAndTheCatalogsAdjustment() {
		Hall target = hall("Stadthalle");
		RestorePlan<Hall> plan = plan(target);
		List<String> calls = new ArrayList<>();
		AbstractBackupCatalog<Hall> catalog = catalog((id, point) -> {
			calls.add("prepare");
			assertEquals("h1", id);
			assertSame(POINT, point);
			return plan;
		}, (given, adjust) -> {
			calls.add("commit");
			assertSame(plan, given);
			return new Restored<>(target, adjust.apply(target));
		});

		Prepared prepared = catalog.prepare(ENTRY, POINT);
		assertEquals(List.of("prepare"), calls);
		RestoreResult result = catalog.commit(prepared);

		assertEquals(List.of("prepare", "commit"), calls);
		assertTrue(result.success());
		assertTrue(result.notes().isEmpty());
		assertTrue(result.message().startsWith("Halle \"Stadthalle\" auf den Stand vom "), result.message());
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
		AbstractBackupCatalog<Hall> catalog = new AbstractBackupCatalog<>("Halle", List::of, id -> List.of(POINT),
				(id, point) -> null, (plan, adjust) -> null, Hall::getName) {
			@Override
			public String restoreWarning() {
				return "";
			}
		};

		assertEquals(List.of(POINT), catalog.versions("h1"));
	}
}
