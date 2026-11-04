package de.eltviller_carneval_verein.karten.ui.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.repository.SavePoint;
import de.eltviller_carneval_verein.karten.ui.backup.BackupCatalog.Entry;

class BackupRestoreDialogTest {

	private static final ZoneId ZONE = ZoneOffset.ofHours(2);

	private static SavePoint point(String saveId, String utc, String operator, int unitCount) {
		return new SavePoint(saveId, Instant.parse(utc), "Kasse1", operator, unitCount);
	}

	@Test
	void entryText_marksDeletedEntries() {
		assertEquals("Alpha", BackupRestoreDialog.entryText(new Entry("1", "Alpha", false)));
		assertEquals("Alpha (gelöscht)", BackupRestoreDialog.entryText(new Entry("1", "Alpha", true)));
	}

	@Test
	void groupByDayNewestFirst() {
		// Eingabe neueste zuerst; 22:30 UTC ist in der Zone +02:00 schon der nächste Tag
		SavePoint lateEvening = point("a", "2026-10-26T22:30:00Z", "Anna", 1);
		SavePoint noon = point("b", "2026-10-26T12:00:00Z", "Anna", 2);
		SavePoint morning = point("c", "2026-10-26T08:00:00Z", "Anna", 3);
		SavePoint earlierDay = point("d", "2026-10-25T09:00:00Z", "Anna", 4);

		Map<LocalDate, List<SavePoint>> days = BackupRestoreDialog.groupByDay(List.of(lateEvening, noon, morning, earlierDay), ZONE);

		assertEquals(List.of(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 25)), new ArrayList<>(days.keySet()));
		assertEquals(List.of(lateEvening), days.get(LocalDate.of(2026, 10, 27)));
		assertEquals(List.of(noon, morning), days.get(LocalDate.of(2026, 10, 26)));
		assertEquals(List.of(earlierDay), days.get(LocalDate.of(2026, 10, 25)));
	}

	@Test
	void groupByDayOfNothingIsEmpty() {
		assertEquals(Map.of(), BackupRestoreDialog.groupByDay(List.of(), ZONE));
	}

	@Test
	void savePointTextShowsTimeMachineOperatorAndCount() {
		assertEquals("14:30:12 · Kasse1 · Anna · 3 Änderungen", BackupRestoreDialog.savePointText(point("a", "2026-10-26T12:30:12Z", "Anna", 3), ZONE));
	}

	@Test
	void savePointTextOmitsEmptyOperator() {
		assertEquals("14:30:12 · Kasse1 · 3 Änderungen", BackupRestoreDialog.savePointText(point("a", "2026-10-26T12:30:12Z", null, 3), ZONE));
		assertEquals("14:30:12 · Kasse1 · 3 Änderungen", BackupRestoreDialog.savePointText(point("a", "2026-10-26T12:30:12Z", "", 3), ZONE));
		assertEquals("14:30:12 · Kasse1 · 3 Änderungen", BackupRestoreDialog.savePointText(point("a", "2026-10-26T12:30:12Z", "  ", 3), ZONE));
	}

	@Test
	void savePointTextNamesUnknownMachine() {
		Instant at = Instant.parse("2026-10-26T12:30:12Z");

		assertEquals("14:30:12 · unbekannter Rechner · Anna · 3 Änderungen",
				BackupRestoreDialog.savePointText(new SavePoint("a", at, null, "Anna", 3), ZONE));
		assertEquals("14:30:12 · unbekannter Rechner · Anna · 3 Änderungen",
				BackupRestoreDialog.savePointText(new SavePoint("a", at, "", "Anna", 3), ZONE));
		assertEquals("14:30:12 · unbekannter Rechner · 3 Änderungen",
				BackupRestoreDialog.savePointText(new SavePoint("a", at, "  ", null, 3), ZONE));
	}

	@Test
	void savePointTextUsesSingularForOneChange() {
		assertEquals("14:30:12 · Kasse1 · Anna · 1 Änderung", BackupRestoreDialog.savePointText(point("a", "2026-10-26T12:30:12Z", "Anna", 1), ZONE));
	}

	@Test
	void dayTextUsesGermanDateFormat() {
		assertEquals("27.10.2026", BackupRestoreDialog.dayText(LocalDate.of(2026, 10, 27)));
	}

	@Test
	void confirmTextNamesTargetTimeAndWarning() {
		SavePoint point = point("a", "2026-10-26T12:30:12Z", "Anna", 1);
		Entry entry = new Entry("1", "Alpha", false);

		assertEquals("Event \"Alpha\" auf den Stand vom 26.10.2026 14:30:12 zurücksetzen?\n\nWarnung. Der aktuelle Stand bleibt als Speicherstand erhalten.",
				BackupRestoreDialog.confirmText("Event", entry, point, "Warnung.", ZONE));
	}
}
