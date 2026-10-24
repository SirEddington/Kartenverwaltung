package de.eltviller_carneval_verein.karten.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.mirror.MirrorSetup.Action;
import de.eltviller_carneval_verein.karten.mirror.MirrorSetup.CancelledException;
import de.eltviller_carneval_verein.karten.mirror.MirrorSetup.Decision;
import de.eltviller_carneval_verein.karten.validation.ValidationResult;

class MirrorSetupTest {

	// Feste Basiszeit (Millisekunden), damit die Zeitvergleiche nicht von der Uhr abhängen
	private static final long T0 = 1_700_000_000_000L;

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 14, 30, 5);
	private static final String SAFETY = "vor-Übernahme-2026-10-24_14-30-05";

	@TempDir
	Path local;

	@TempDir
	Path target;

	private static Path write(Path root, String rel, String text) throws IOException {
		Path file = root.resolve(rel);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
		Files.setLastModifiedTime(file, FileTime.fromMillis(T0));
		return file;
	}

	private static String read(Path root, String rel) throws IOException {
		return Files.readString(root.resolve(rel), StandardCharsets.UTF_8);
	}

	private static List<String> allFiles(Path root) throws IOException {
		try (Stream<Path> stream = Files.walk(root)) {
			return stream.filter(Files::isRegularFile)
					.map(p -> root.relativize(p).toString().replace('\\', '/'))
					.sorted()
					.toList();
		}
	}

	private static void assertIssue(String code, ValidationResult result) {
		assertFalse(result.isValid());
		assertEquals(code, result.mostSevere().orElseThrow().code());
	}

	@Test
	void checkCreatesMissingBaseFolder() {
		Path dir = target.resolve("neu").resolve("spiegel");

		ValidationResult result = MirrorSetup.check(dir.toString());

		assertTrue(result.isValid());
		assertTrue(Files.isDirectory(dir));
		// Die Schreibprobe hinterlässt nichts
		assertEquals(0, dir.toFile().list().length);
	}

	@Test
	void checkTrimsTheInput() {
		ValidationResult result = MirrorSetup.check("  " + target + "  ");

		assertTrue(result.isValid());
	}

	@Test
	void checkRejectsBaseFolderInsideAFile() throws IOException {
		Path file = write(target, "datei.txt", "x");

		assertIssue("SET-002", MirrorSetup.check(file.resolve("unterordner").toString()));
	}

	@Test
	void checkRejectsAFileAsMirrorDir() throws IOException {
		Path file = write(target, "datei.txt", "x");

		assertIssue("SET-002", MirrorSetup.check(file.toString()));
	}

	@Test
	void checkRejectsAnUnusablePathString() {
		// Ein NUL-Zeichen ist auf jedem System ein ungültiger Pfad
		assertIssue("SET-002", MirrorSetup.check("a\0b"));
	}

	@Test
	void checkRejectsBlankInput() {
		assertIssue("SET-002", MirrorSetup.check("   "));
		assertIssue("SET-002", MirrorSetup.check(null));
	}

	@Test
	void unwritableFolderGivesSet003() throws IOException {
		Path dir = Files.createDirectories(target.resolve("gesperrt"));
		try {
			dir.toFile().setWritable(false);
			assumeTrue(!Files.isWritable(dir), "Das Betriebssystem ignoriert setWritable(false) bei Ordnern");

			assertIssue("SET-003", MirrorSetup.check(dir.toString()));
		} finally {
			dir.toFile().setWritable(true);
		}
	}

	@Test
	void decideEmptyTargetMeansPushLocal() throws IOException {
		write(local, "events_data/E1.json", "lokal");

		assertEquals(Action.PUSH_LOCAL, MirrorSetup.decide(local, target));
	}

	@Test
	void decideBothEmptyMeansNothing() throws IOException {
		assertEquals(Action.NOTHING, MirrorSetup.decide(local, target));
	}

	@Test
	void decideEmptyLocalWithTargetDataMeansAdopt() throws IOException {
		write(target, "halls_data/H1.json", "ziel");

		assertEquals(Action.ADOPT_TARGET, MirrorSetup.decide(local, target));
	}

	@Test
	void decideIdenticalMeansNothing() throws IOException {
		write(local, "events_data/E1.json", "gleich");
		write(target, "events_data/E1.json", "gleich");

		assertEquals(Action.NOTHING, MirrorSetup.decide(local, target));
	}

	@Test
	void decideDifferentMeansAsk() throws IOException {
		write(local, "events_data/E1.json", "lokal");
		write(target, "events_data/E1.json", "anders");

		assertEquals(Action.ASK, MirrorSetup.decide(local, target));
	}

	@Test
	void pushLocalReplacesTheTargetExactly() throws IOException {
		write(local, "events_data/E1.json", "lokal");
		write(local, "halls_data/H1.json", "halle");
		write(target, "events_data/E1.json", "alt");
		write(target, "events_data/E2.json", "ueberzaehlig");
		write(target, "eigene-datei.txt", "gehört dem Nutzer");

		MirrorSetup.execute(Action.PUSH_LOCAL, local, target, NOW);

		assertEquals("lokal", read(target, "events_data/E1.json"));
		assertEquals("halle", read(target, "halls_data/H1.json"));
		assertFalse(Files.exists(target.resolve("events_data/E2.json")));
		assertEquals("gehört dem Nutzer", read(target, "eigene-datei.txt"));
		// Das Ziel wird nie gesichert; der lokale Ordner bleibt unberührt
		assertEquals(List.of("events_data/E1.json", "halls_data/H1.json"), allFiles(local));
	}

	@Test
	void adoptCreatesSafetyBackupAndReplacesLocal() throws IOException {
		write(local, "events_data/E1.json", "lokal-alt");
		write(local, "events_data/backups/E1/b.json", "lokal-backup");
		write(local, "halls_data/H1.json", "lokal-halle");
		write(target, "events_data/E1.json", "ziel");
		write(target, "events_data/E9.json", "ziel-neu");

		MirrorSetup.execute(Action.ADOPT_TARGET, local, target, NOW);

		// Sicherung direkt unter dem lokalen Ordner, mit den alten Daten
		Path safety = local.resolve(SAFETY);
		assertTrue(Files.isDirectory(safety));
		assertEquals("lokal-alt", read(safety, "events_data/E1.json"));
		assertEquals("lokal-backup", read(safety, "events_data/backups/E1/b.json"));
		assertEquals("lokal-halle", read(safety, "halls_data/H1.json"));
		// Lokal entspricht danach dem Ziel
		assertEquals("ziel", read(local, "events_data/E1.json"));
		assertEquals("ziel-neu", read(local, "events_data/E9.json"));
		assertFalse(Files.exists(local.resolve("halls_data/H1.json")));
		assertFalse(Files.exists(local.resolve("events_data/backups")));
		assertTrue(MirrorSync.compare(target, local).isEmpty());
		// Die Sicherung selbst zählt für spätere Vergleiche nicht als Daten
		assertTrue(MirrorSync.compare(local, target).isEmpty());
		// Das Ziel bleibt unverändert
		assertEquals(List.of("events_data/E1.json", "events_data/E9.json"), allFiles(target));
	}

	@Test
	void adoptWithEmptyLocalMakesNoBackup() throws IOException {
		write(target, "events_data/E1.json", "ziel");

		MirrorSetup.execute(Action.ADOPT_TARGET, local, target, NOW);

		assertFalse(Files.exists(local.resolve(SAFETY)));
		assertEquals("ziel", read(local, "events_data/E1.json"));
	}

	@Test
	void adoptBacksUpLocalFilesThatAreNotEntityFiles() throws IOException {
		// Nur ein Backup, keine Entitätsdatei: wird beim Übernehmen trotzdem gelöscht, also vorher gesichert
		write(local, "events_data/backups/E1/b.json", "nur-backup");
		write(target, "events_data/E1.json", "ziel");

		MirrorSetup.execute(Action.ADOPT_TARGET, local, target, NOW);

		assertEquals("nur-backup", read(local.resolve(SAFETY), "events_data/backups/E1/b.json"));
		assertFalse(Files.exists(local.resolve("events_data/backups")));
	}

	@Test
	void adoptLeavesLocalUntouchedWhenTheSafetyCopyFails() throws IOException {
		write(local, "events_data/E1.json", "lokal-alt");
		write(target, "events_data/E1.json", "ziel");
		// Ein Hindernis genau dort, wo die Sicherung liegen soll
		Path blocker = write(local, SAFETY + "/inhalt.txt", "früherer Stand");

		assertThrows(IOException.class, () -> MirrorSetup.execute(Action.ADOPT_TARGET, local, target, NOW));

		assertEquals("lokal-alt", read(local, "events_data/E1.json"));
		assertEquals("früherer Stand", Files.readString(blocker, StandardCharsets.UTF_8));
		assertEquals("ziel", read(target, "events_data/E1.json"));
	}

	@Test
	void nothingAndAskDoNothing() throws IOException {
		write(local, "events_data/E1.json", "lokal");
		write(target, "events_data/E1.json", "ziel");

		MirrorSetup.execute(Action.NOTHING, local, target, NOW);
		MirrorSetup.execute(Action.ASK, local, target, NOW);

		assertEquals("lokal", read(local, "events_data/E1.json"));
		assertEquals("ziel", read(target, "events_data/E1.json"));
		assertFalse(Files.exists(local.resolve(SAFETY)));
	}

	@Test
	void resolveMapsDecisions() {
		assertEquals(Action.ADOPT_TARGET, MirrorSetup.resolve(Decision.ADOPT_TARGET));
		assertEquals(Action.PUSH_LOCAL, MirrorSetup.resolve(Decision.WRITE_LOCAL));
		assertThrows(CancelledException.class, () -> MirrorSetup.resolve(Decision.CANCEL));
	}
}
