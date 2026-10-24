package de.eltviller_carneval_verein.karten.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.mirror.MirrorSync.Differences;

class MirrorSyncTest {

	// Feste Basiszeit (Millisekunden), damit die Zeitvergleiche nicht von der Uhr abhängen
	private static final long T0 = 1_700_000_000_000L;

	@TempDir
	Path local;

	@TempDir
	Path target;

	private static Path write(Path root, String rel, String text, long mtimeMillis) throws IOException {
		Path file = root.resolve(rel);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
		Files.setLastModifiedTime(file, FileTime.fromMillis(mtimeMillis));
		return file;
	}

	private static String read(Path root, String rel) throws IOException {
		return Files.readString(root.resolve(rel), StandardCharsets.UTF_8);
	}

	private static long mtime(Path root, String rel) throws IOException {
		return Files.getLastModifiedTime(root.resolve(rel)).toMillis();
	}

	private static boolean exists(Path root, String rel) {
		return Files.exists(root.resolve(rel));
	}

	private static List<String> allFiles(Path root) throws IOException {
		try (Stream<Path> stream = Files.walk(root)) {
			return stream.filter(Files::isRegularFile)
					.map(p -> root.relativize(p).toString().replace('\\', '/'))
					.sorted()
					.toList();
		}
	}

	@Test
	void compareReportsMissingChangedExtra() throws IOException {
		write(local, "events_data/E1.json", "eins", T0);
		write(local, "events_data/E2.json", "zwei", T0);
		write(local, "halls_data/H1.json", "halle", T0);
		write(target, "events_data/E2.json", "zwei-anders", T0);
		write(target, "halls_data/H1.json", "halle", T0);
		write(target, "halls_data/H9.json", "alt", T0);

		Differences diff = MirrorSync.compare(local, target);

		assertEquals(List.of("events_data/E1.json"), diff.missing());
		assertEquals(List.of("events_data/E2.json"), diff.changed());
		assertEquals(List.of("halls_data/H9.json"), diff.extra());
		assertFalse(diff.isEmpty());
	}

	@Test
	void copiesMissingFiles() throws IOException {
		write(local, "events_data/E1.json", "eins", T0);
		write(local, "halls_data/H1.json", "halle", T0);

		MirrorSync.reconcile(local, target);

		assertEquals("eins", read(target, "events_data/E1.json"));
		assertEquals("halle", read(target, "halls_data/H1.json"));
		assertTrue(MirrorSync.compare(local, target).isEmpty());
	}

	@Test
	void overwritesFilesWithDifferentSize() throws IOException {
		write(local, "events_data/E1.json", "neu und länger", T0);
		write(target, "events_data/E1.json", "alt", T0);

		MirrorSync.reconcile(local, target);

		assertEquals("neu und länger", read(target, "events_data/E1.json"));
	}

	@Test
	void overwritesFilesWithDifferentTimeBeyondTolerance() throws IOException {
		write(local, "events_data/E1.json", "neu1", T0 + 5000);
		write(target, "events_data/E1.json", "alt1", T0);

		assertEquals(List.of("events_data/E1.json"), MirrorSync.compare(local, target).changed());

		MirrorSync.reconcile(local, target);

		assertEquals("neu1", read(target, "events_data/E1.json"));
	}

	@Test
	void ignoresTimeDifferenceWithinTolerance() throws IOException {
		write(local, "events_data/E1.json", "gleich", T0 + 1500);
		write(target, "events_data/E1.json", "gleich", T0);

		assertTrue(MirrorSync.compare(local, target).isEmpty());
	}

	@Test
	void deletesExtraFilesInTarget() throws IOException {
		write(local, "events_data/E1.json", "eins", T0);
		write(target, "events_data/E1.json", "eins", T0);
		write(target, "events_data/E7.json", "weg", T0);
		write(target, "halls_data/H7.json", "weg", T0);
		write(target, "halls_data/backups/H7/alt.json", "weg", T0);

		MirrorSync.reconcile(local, target);

		assertEquals(List.of("events_data/E1.json"), allFiles(target));
		assertTrue(MirrorSync.compare(local, target).isEmpty());
	}

	@Test
	void skipsTmpFiles() throws IOException {
		write(local, "events_data/E1.json", "eins", T0);
		write(local, "events_data/E1.json.tmp", "halb", T0);
		write(target, "events_data/E1.json", "eins", T0);
		write(target, "events_data/fremd.tmp", "bleibt", T0);

		assertTrue(MirrorSync.compare(local, target).isEmpty());

		MirrorSync.reconcile(local, target);

		assertFalse(exists(target, "events_data/E1.json.tmp"));
		assertTrue(exists(target, "events_data/fremd.tmp"));
	}

	@Test
	void leavesFilesOutsideTheTwoFoldersAlone() throws IOException {
		write(local, "events_data/E1.json", "eins", T0);
		write(local, "settings.json", "lokal", T0);
		write(target, "other.txt", "bleibt", T0);
		write(target, "sub/datei.txt", "bleibt auch", T0);

		MirrorSync.reconcile(local, target);

		assertEquals("bleibt", read(target, "other.txt"));
		assertEquals("bleibt auch", read(target, "sub/datei.txt"));
		assertFalse(exists(target, "settings.json"));
		assertTrue(MirrorSync.compare(local, target).isEmpty());
	}

	@Test
	void copiesBackupSubtree() throws IOException {
		write(local, "events_data/E1.json", "eins", T0);
		write(local, "events_data/backups/E1/2026-01-01.json", "b1", T0);
		write(local, "halls_data/H1.json", "halle", T0);
		write(local, "halls_data/backups/H1/2026-02-02.json", "b2", T0);

		MirrorSync.reconcile(local, target);

		assertEquals("b1", read(target, "events_data/backups/E1/2026-01-01.json"));
		assertEquals("b2", read(target, "halls_data/backups/H1/2026-02-02.json"));
	}

	@Test
	void appliesTheModifiedTimeOfTheSource() throws IOException {
		write(local, "events_data/E1.json", "eins", T0 + 12_000);

		MirrorSync.reconcile(local, target);

		// Dateisysteme stempeln unterschiedlich fein, daher nur innerhalb der Toleranz prüfen
		assertTrue(Math.abs(mtime(target, "events_data/E1.json") - (T0 + 12_000)) < MirrorSync.MTIME_TOLERANCE_MS);
	}

	@Test
	void missingLocalFoldersAreTreatedAsEmpty() throws IOException {
		// lokal gibt es weder events_data noch halls_data, das Ziel ist leer
		MirrorSync.reconcile(local, target);

		assertTrue(MirrorSync.compare(local, target).isEmpty());
	}

	@Test
	void abortsWhenLocalHasNoEntityFilesButTargetHas() throws IOException {
		write(local, "events_data/backups/E1/2026-01-01.json", "nur Backup", T0);
		write(target, "events_data/E1.json", "eins", T0);
		write(target, "halls_data/H1.json", "halle", T0);
		write(target, "halls_data/backups/H1/alt.json", "b", T0);

		assertThrows(MirrorAbortedException.class, () -> MirrorSync.reconcile(local, target));

		assertEquals(List.of("events_data/E1.json", "halls_data/H1.json", "halls_data/backups/H1/alt.json").stream().sorted().toList(),
				allFiles(target));
		assertEquals("eins", read(target, "events_data/E1.json"));
		// Auch das Backup aus dem lokalen Ordner wurde nicht angelegt: es wurde gar nichts geändert
		assertFalse(exists(target, "events_data/backups/E1/2026-01-01.json"));
	}

	@Test
	void reconcileOfEmptyLocalIntoEmptyTargetIsFine() throws IOException {
		Files.createDirectories(local.resolve("events_data"));
		Files.createDirectories(target.resolve("halls_data"));

		MirrorSync.reconcile(local, target);

		assertTrue(allFiles(target).isEmpty());
	}

	@Test
	void hasEntityFilesIgnoresBackupsAndTmp() throws IOException {
		write(local, "events_data/backups/E1/2026-01-01.json", "b", T0);
		write(local, "events_data/E1.json.tmp", "halb", T0);
		write(local, "halls_data/notiz.txt", "x", T0);

		assertFalse(MirrorSync.hasEntityFiles(local));
		assertFalse(MirrorSync.hasEntityFiles(target.resolve("gibt-es-nicht")));

		write(local, "halls_data/H1.json", "halle", T0);

		assertTrue(MirrorSync.hasEntityFiles(local));
	}

	@Test
	void pushEntityCopiesJsonAndBackupSubtree() throws IOException {
		write(local, "events_data/E1.json", "eins", T0);
		write(local, "events_data/backups/E1/b1.json", "b1", T0);
		write(local, "events_data/E2.json", "zwei", T0);
		write(local, "events_data/backups/E2/b2.json", "b2", T0);

		MirrorSync.pushEntity(local, target, MirrorSync.EVENTS, "E1");

		assertEquals(List.of("events_data/E1.json", "events_data/backups/E1/b1.json"), allFiles(target));
		assertEquals("eins", read(target, "events_data/E1.json"));
	}

	@Test
	void pushEntityRemovesExtraFilesInBackupSubtree() throws IOException {
		write(local, "halls_data/H1.json", "halle", T0);
		write(local, "halls_data/backups/H1/neu.json", "neu", T0);
		write(target, "halls_data/H1.json", "halle", T0);
		write(target, "halls_data/backups/H1/alt.json", "alt", T0);
		write(target, "halls_data/backups/H2/fremd.json", "bleibt", T0);

		MirrorSync.pushEntity(local, target, MirrorSync.HALLS, "H1");

		assertEquals(List.of("halls_data/H1.json", "halls_data/backups/H1/neu.json", "halls_data/backups/H2/fremd.json"),
				allFiles(target));
	}

	@Test
	void pushEntityDeletesTargetFileWhenLocalIsGone() throws IOException {
		write(local, "events_data/E2.json", "zwei", T0);
		write(target, "events_data/E1.json", "eins", T0);
		write(target, "events_data/E2.json", "zwei", T0);

		MirrorSync.pushEntity(local, target, MirrorSync.EVENTS, "E1");

		assertFalse(exists(target, "events_data/E1.json"));
		assertTrue(exists(target, "events_data/E2.json"));
	}

	@Test
	void deleteEntityRemovesJsonAndKeepsOtherEntities() throws IOException {
		write(local, "events_data/E2.json", "zwei", T0);
		write(local, "events_data/backups/E1/vor-loeschen.json", "b", T0);
		write(target, "events_data/E1.json", "eins", T0);
		write(target, "events_data/E2.json", "zwei", T0);
		write(target, "events_data/E3.json", "drei, nicht lokal", T0);

		MirrorSync.deleteEntity(local, target, MirrorSync.EVENTS, "E1");

		assertFalse(exists(target, "events_data/E1.json"));
		assertEquals("zwei", read(target, "events_data/E2.json"));
		// E3 gehört nicht zur gelöschten Entität und wird nicht angefasst
		assertEquals("drei, nicht lokal", read(target, "events_data/E3.json"));
		assertEquals("b", read(target, "events_data/backups/E1/vor-loeschen.json"));
	}

	@Test
	void deleteLastEntityIsAllowed() throws IOException {
		write(target, "halls_data/H1.json", "halle", T0);

		MirrorSync.deleteEntity(local, target, MirrorSync.HALLS, "H1");

		assertFalse(MirrorSync.hasEntityFiles(target));
	}

	@Test
	void copyAllReplacesTargetExactlyEvenWhenLocalIsEmpty() throws IOException {
		// "from" ist leer, "to" hat Daten: copyAll hat keine Sicherung gegen leer und räumt das Ziel leer
		write(target, "events_data/E1.json", "eins", T0);
		write(target, "halls_data/backups/H1/b.json", "b", T0);
		write(target, "other.txt", "bleibt", T0);

		MirrorSync.copyAll(local, target);

		assertEquals(List.of("other.txt"), allFiles(target));
		assertTrue(MirrorSync.compare(local, target).isEmpty());
	}

	@Test
	void failedCopyLeavesNoTmpFile() throws IOException {
		write(local, "events_data/E1.json", "eins", T0);
		write(target, "events_data/E1.json/inhalt.txt", "Ordner statt Datei", T0);

		assertThrows(IOException.class, () -> MirrorSync.reconcile(local, target));

		try (Stream<Path> stream = Files.walk(target)) {
			assertTrue(stream.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
		}
		assertEquals("Ordner statt Datei", read(target, "events_data/E1.json/inhalt.txt"));
	}

	@Test
	void replacesTypeMismatchesBetweenFileAndEmptyFolder() throws IOException {
		write(local, "events_data/E1.json", "datei", T0);
		write(local, "halls_data/backups/H1/b.json", "b", T0);
		write(local, "halls_data/H1.json", "halle", T0);
		Files.createDirectories(target.resolve("events_data/E1.json"));
		write(target, "halls_data/backups", "Datei statt Ordner", T0);

		assertEquals(List.of("events_data/E1.json"), MirrorSync.compare(local, target).changed());

		MirrorSync.reconcile(local, target);

		assertEquals("datei", read(target, "events_data/E1.json"));
		assertEquals("b", read(target, "halls_data/backups/H1/b.json"));
		assertTrue(MirrorSync.compare(local, target).isEmpty());
	}
}
