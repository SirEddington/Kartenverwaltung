package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncPositionsTest {

	private static final String SHARED = "\\\\server\\karten";
	private static final String OTHER_SHARED = "D:\\nextcloud\\karten";
	private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbb";

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("sync-state.json");
	}

	private void write(String content) throws IOException {
		Files.writeString(file(), content, StandardCharsets.UTF_8);
	}

	@Test
	void brokenFileMeansFullSync() throws IOException {
		// fehlende Datei
		assertTrue(new SyncPositions(file(), SHARED).wasBroken());

		// kein JSON
		write("{ das ist kein json");
		assertTrue(new SyncPositions(file(), SHARED).wasBroken());

		// kein Objekt
		write("[1, 2, 3]");
		assertTrue(new SyncPositions(file(), SHARED).wasBroken());

		// leere Datei
		write("");
		assertTrue(new SyncPositions(file(), SHARED).wasBroken());

		// gültig, aber ohne Eintrag für diesen gemeinsamen Ordner
		write("{\"" + OTHER_SHARED.replace("\\", "\\\\") + "\": {\"" + A + "\": 3}}");
		SyncPositions withoutEntry = new SyncPositions(file(), SHARED);
		assertTrue(withoutEntry.wasBroken());
		assertEquals(0L, withoutEntry.position(A));

		// Nummer ist kein Zahlenwert
		write("{\"" + SHARED.replace("\\", "\\\\") + "\": {\"" + A + "\": \"viele\"}}");
		SyncPositions badValue = new SyncPositions(file(), SHARED);
		assertTrue(badValue.wasBroken());
		assertTrue(badValue.machines().isEmpty());

		// Datei ist ein Ordner und damit nicht lesbar
		Path folder = Files.createDirectory(dir.resolve("ordner"));
		assertTrue(new SyncPositions(folder, SHARED).wasBroken());
	}

	@Test
	void validFileIsNotBroken() throws IOException {
		write("{\"" + SHARED.replace("\\", "\\\\") + "\": {\"" + A + "\": 7, \"" + B + "\": 0}}");

		SyncPositions positions = new SyncPositions(file(), SHARED);

		assertFalse(positions.wasBroken());
		assertEquals(7L, positions.position(A));
		assertEquals(0L, positions.position(B));
		assertEquals(Set.of(A, B), positions.machines());
	}

	@Test
	void emptyEntryForTheFolderIsNotBroken() throws IOException {
		write("{\"" + SHARED.replace("\\", "\\\\") + "\": {}}");

		assertFalse(new SyncPositions(file(), SHARED).wasBroken());
	}

	@Test
	void unknownMachineIsAtZero() {
		SyncPositions positions = new SyncPositions(file(), SHARED);

		assertEquals(0L, positions.position(A));
		assertTrue(positions.machines().isEmpty());
	}

	@Test
	void positionsAreKeptPerSharedFolder() throws IOException {
		SyncPositions first = new SyncPositions(file(), SHARED);
		first.set(A, 5);
		first.set(B, 9);
		first.save();

		// der zweite Ordner teilt sich die Datei und darf den ersten nicht überschreiben
		SyncPositions second = new SyncPositions(file(), OTHER_SHARED);
		assertTrue(second.wasBroken());
		second.set(A, 2);
		second.save();

		SyncPositions firstAgain = new SyncPositions(file(), SHARED);
		assertFalse(firstAgain.wasBroken());
		assertEquals(5L, firstAgain.position(A));
		assertEquals(9L, firstAgain.position(B));
		SyncPositions secondAgain = new SyncPositions(file(), OTHER_SHARED);
		assertFalse(secondAgain.wasBroken());
		assertEquals(2L, secondAgain.position(A));
		assertEquals(0L, secondAgain.position(B));
	}

	@Test
	void saveReplacesABrokenFile() throws IOException {
		write("{ kaputt");
		SyncPositions positions = new SyncPositions(file(), SHARED);
		positions.set(A, 4);

		positions.save();

		assertTrue(positions.wasBroken(), "wasBroken beschreibt den Zustand beim Laden");
		SyncPositions reloaded = new SyncPositions(file(), SHARED);
		assertFalse(reloaded.wasBroken());
		assertEquals(4L, reloaded.position(A));
	}

	@Test
	void saveLeavesNoTemporaryFileAndCreatesTheFolder() throws IOException {
		Path nested = dir.resolve("neu").resolve("sync-state.json");
		SyncPositions positions = new SyncPositions(nested, SHARED);
		positions.set(A, 1);
		positions.set(A, 2);

		positions.save();

		try (Stream<Path> files = Files.list(nested.getParent())) {
			assertEquals(1L, files.count());
		}
		assertEquals(2L, new SyncPositions(nested, SHARED).position(A));
	}

	@Test
	void negativeNumberIsRejected() {
		SyncPositions positions = new SyncPositions(file(), SHARED);

		assertThrows(IllegalArgumentException.class, () -> positions.set(A, -1));
	}
}
