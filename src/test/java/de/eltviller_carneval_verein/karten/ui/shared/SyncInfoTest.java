package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.store.SkippedUnit;
import de.eltviller_carneval_verein.karten.store.SyncState;
import de.eltviller_carneval_verein.karten.store.UnitKey;

class SyncInfoTest {

	private static final ZoneId UTC = ZoneOffset.UTC;
	private static final String DIR = "D:\\Nextcloud\\Karten";
	private static final UnitKey EVENT = UnitKey.event("e1");
	private static final UnitKey BOOKING = UnitKey.booking("e1", "p1", "A1");

	private static SyncInfo info(boolean complete, Double progress, List<SkippedUnit> skipped,
			Function<UnitKey, String> labels) {
		return SyncInfo.of(DIR, Instant.parse("2026-10-31T09:05:07Z"), null, complete, progress, skipped, labels, UTC);
	}

	@Test
	void rowsUseLabelsAndSubtreeSuffix() {
		List<SkippedUnit> skipped = List.of(new SkippedUnit(EVENT, true, "Datei gesperrt"),
				new SkippedUnit(BOOKING, false, "Datei beschädigt"));
		SyncInfo info = info(false, null, skipped, unit -> unit.equals(EVENT) ? "Prunksitzung" : "Prunksitzung › A1");

		assertEquals(2, info.rows().size());
		assertEquals(new SyncInfo.Row("Prunksitzung mit allem darunter", "Datei gesperrt"), info.rows().get(0));
		assertEquals(new SyncInfo.Row("Prunksitzung › A1", "Datei beschädigt"), info.rows().get(1));
		assertEquals("Die Kartenverwaltung versucht es beim nächsten Abgleich erneut, spätestens nach 10 Minuten.",
				info.listHint());
	}

	@Test
	void rowFallsBackToThePathWithoutALabel() {
		SyncInfo nullLabel = info(false, null, List.of(new SkippedUnit(EVENT, false, "x")), unit -> null);
		SyncInfo failing = info(false, null, List.of(new SkippedUnit(EVENT, true, "x")), unit -> {
			throw new IllegalStateException("kein Name");
		});

		assertEquals(EVENT.relativePath(), nullLabel.rows().get(0).entry());
		assertEquals(EVENT.relativePath() + " mit allem darunter", failing.rows().get(0).entry());
	}

	@Test
	void firstRunLineForEachState() {
		assertEquals("Erster Abgleich: vollständig", info(true, null, List.of(), unit -> null).firstRunLine());
		assertEquals("Erster Abgleich: läuft (42 %)", info(false, 0.429, List.of(), unit -> null).firstRunLine());
		assertEquals("Erster Abgleich: unvollständig", info(false, null, List.of(), unit -> null).firstRunLine());
		// vollständig geht vor: spätere Läufe haben auch einen Fortschritt
		assertEquals("Erster Abgleich: vollständig", info(true, 0.5, List.of(), unit -> null).firstRunLine());
	}

	@Test
	void emptyListSaysNone() {
		SyncInfo info = info(true, null, List.of(), unit -> null);

		assertTrue(info.rows().isEmpty());
		assertEquals("Keine", info.listHint());
	}

	@Test
	void foldersLastSyncAndCauseLines() {
		SyncInfo withCause = SyncInfo.of(DIR, Instant.parse("2026-10-31T09:05:07Z"), "Die Datei ist gesperrt.", true, null,
				List.of(), unit -> null, UTC);
		SyncInfo never = SyncInfo.of(DIR, null, null, true, null, List.of(), unit -> null, UTC);

		assertEquals("Ordner: D:\\Nextcloud\\Karten", withCause.folderLine());
		assertEquals("Letzter Abgleich: 31.10.2026 09:05:07", withCause.lastSyncLine());
		assertEquals("Ursache: Die Datei ist gesperrt.", withCause.causeLine());
		assertEquals("Letzter Abgleich: noch keiner", never.lastSyncLine());
		assertEquals("", never.causeLine(), "ohne Ursache keine Zeile");
		assertEquals("", SyncInfo.of(DIR, null, "  ", true, null, List.of(), unit -> null, UTC).causeLine());
	}

	@Test
	void causeWhileOfflineIsTheUnreachableText() {
		assertEquals("SYS-005: Der gemeinsame Ordner D:\\Nextcloud\\Karten ist nicht erreichbar. Du kannst weiterarbeiten: "
				+ "Neue Verkäufe und Änderungen sind vorläufig und werden hochgeladen, sobald der Ordner wieder erreichbar ist.",
				SyncInfo.cause(SyncState.OFFLINE, DIR, null));
		// auch eine ältere Störung tritt hinter den Ausfall zurück, bleibt aber für die Zeit danach erhalten
		assertTrue(SyncInfo.cause(SyncState.OFFLINE, DIR, "SYS-006: alt").startsWith("SYS-005: "));
	}

	@Test
	void causeOtherwiseIsTheLastFailure() {
		assertEquals("SYS-006: gescheitert", SyncInfo.cause(SyncState.FEHLER, DIR, "SYS-006: gescheitert"));
		assertEquals(null, SyncInfo.cause(SyncState.AKTUELL, DIR, null));
		assertEquals(null, SyncInfo.cause(null, DIR, null));
	}

	@Test
	void defaultZoneOverloadWorks() {
		SyncInfo info = SyncInfo.of(DIR, Instant.parse("2026-10-31T09:05:07Z"), null, true, null, List.of(), unit -> null);

		assertTrue(info.lastSyncLine().startsWith("Letzter Abgleich: 31.10.2026 "));
	}
}
