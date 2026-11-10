package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdatesDirTest {

	@TempDir
	Path dir;

	@Test
	void missingSharedFolderIsNeverCreated() {
		Path missing = dir.resolve("getrennt");
		assertEquals(Optional.empty(), UpdatesDir.ensure(missing));
		assertEquals(Optional.empty(), UpdatesDir.ensure(missing, UpdatesDir.STATIONS));
		assertFalse(Files.exists(missing));
	}

	@Test
	void createsUpdatesAndStations() throws IOException {
		assertEquals(Optional.of(dir.resolve("updates")), UpdatesDir.ensure(dir));
		assertEquals(Optional.of(dir.resolve("updates").resolve("kassen")), UpdatesDir.ensure(dir, UpdatesDir.STATIONS));
		assertTrue(Files.isDirectory(dir.resolve("updates/kassen")));
		assertEquals(Optional.of(dir.resolve("updates").resolve("kassen")), UpdatesDir.ensure(dir, UpdatesDir.STATIONS));
	}

	@Test
	void installerNames() {
		assertTrue(UpdatesDir.isInstaller("ecv-karten-1.1.1.x64.msix"));
		assertTrue(UpdatesDir.isInstaller("ecv-karten-fx132-1.1.0-2.x64.msix"));
		assertFalse(UpdatesDir.isInstaller("ecv-karten-1.1.1.x64 (conflicted copy 2026-11-10 120000).msix"));
		assertFalse(UpdatesDir.isInstaller(".ecv-karten.msix"));
		assertFalse(UpdatesDir.isInstaller("~karv-x.tmp"));
		assertFalse(UpdatesDir.isInstaller("ecv-karten.msix.json"));
		assertFalse(UpdatesDir.isInstaller("..msix"));
	}

	@Test
	void descriptionNames() {
		assertTrue(UpdatesDir.isDescription("ecv-karten-1.1.1.x64.msix.json"));
		assertFalse(UpdatesDir.isDescription("ecv-karten-1.1.1.x64.json"));
		assertFalse(UpdatesDir.isDescription("ecv-karten-1.1.1.x64 (conflicted copy 2026-11-10 120000).msix.json"));
		assertEquals("ecv-karten-1.1.1.x64.msix", UpdatesDir.installerOf("ecv-karten-1.1.1.x64.msix.json"));
	}

	@Test
	void claimNames() {
		assertTrue(UpdatesDir.isClaim("laden-1.1.1.0-abc-123.json"));
		assertEquals(Optional.of("1.1.1.0"), UpdatesDir.claimVersion("laden-1.1.1.0-abc-123.json"));
		assertEquals(Optional.of("abc-123"), UpdatesDir.claimMachine("laden-1.1.1.0-abc-123.json"));
		assertFalse(UpdatesDir.isClaim("laden-1.1.1-abc.json"));
		assertFalse(UpdatesDir.isClaim("laden-1.1.1.0-abc (conflicted copy).json"));
		assertEquals(Optional.empty(), UpdatesDir.claimVersion("x.json"));
	}

	@Test
	void stationNames() {
		assertTrue(UpdatesDir.isStation("aaaa1111-0000-0000-0000-000000000001.json"));
		assertFalse(UpdatesDir.isStation("aaaa (conflicted copy).json"));
		assertFalse(UpdatesDir.isStation("~karv-x.tmp"));
		assertFalse(UpdatesDir.isStation("a.txt"));
	}

	@Test
	void conflictCopies() {
		assertTrue(UpdatesDir.isConflictCopy("ecv-karten-1.1.1.x64 (conflicted copy 2026-11-10 120000).msix"));
		assertTrue(UpdatesDir.isConflictCopy("ecv-karten-1.1.1.x64.msix (conflicted copy 2026-11-10 120000).json"));
		assertFalse(UpdatesDir.isConflictCopy("ecv-karten-1.1.1.x64.msix"));
		assertFalse(UpdatesDir.isConflictCopy("notiz (conflicted copy 2026-11-10 120000).txt"));
	}
}
