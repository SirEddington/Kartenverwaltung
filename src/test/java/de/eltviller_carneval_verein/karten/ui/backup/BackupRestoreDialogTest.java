package de.eltviller_carneval_verein.karten.ui.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.repository.BackupStore.Backup;
import de.eltviller_carneval_verein.karten.ui.backup.BackupCatalog.Entry;

class BackupRestoreDialogTest {

	@Test
	void entryText_marksDeletedEntries() {
		assertEquals("Alpha", BackupRestoreDialog.entryText(new Entry("1", "Alpha", false)));
		assertEquals("Alpha (gelöscht)", BackupRestoreDialog.entryText(new Entry("1", "Alpha", true)));
	}

	@Test
	void versionText_showsTimeAndSizeInKilobytes() {
		LocalDateTime time = LocalDateTime.of(2026, 10, 14, 15, 30, 12);

		assertEquals("14.10.2026 15:30:12 (2 KB)", BackupRestoreDialog.versionText(new Backup("1", Path.of("x.json"), time, 2048)));
		assertEquals("14.10.2026 15:30:12 (1 KB)", BackupRestoreDialog.versionText(new Backup("1", Path.of("x.json"), time, 100)));
		assertEquals("14.10.2026 15:30:12 (461 KB)", BackupRestoreDialog.versionText(new Backup("1", Path.of("x.json"), time, 472_213)));
	}
}
