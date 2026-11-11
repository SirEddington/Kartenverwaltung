package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.update.StationRegistryTest.MutableClock;

class FolderUpdatesTest {

	private static final Instant NOW = Instant.parse("2026-11-10T13:00:00Z");
	private static final String FAMILY = "EcvKartenFx132_0cs1gtds5d9x0";
	private static final String ME = "aaaa1111-0000-0000-0000-000000000001";
	private static final AppVersion OWN = new AppVersion("1.1.0", 1);

	@TempDir
	Path folder;

	private Path updates;
	private MutableClock clock;
	private OwnFiles own;
	private FolderUpdates fu;

	@BeforeEach
	void setUp() throws IOException {
		updates = Files.createDirectories(folder.resolve("updates"));
		clock = new MutableClock(NOW);
		own = new OwnFiles();
		fu = new FolderUpdates(clock, own, tag -> tag.equals(ME) || tag.equals("alte-id"));
	}

	private Path msix(String name, String quad) throws IOException {
		return TestMsix.standard(updates.resolve(name), quad);
	}

	private void describe(Path msix, String quad) throws IOException {
		Files.write(updates.resolve(msix.getFileName() + ".json"),
				FolderUpdates.descriptionJson(quad, FAMILY, UpdateFiles.sha256Hex(msix), Files.size(msix)));
	}

	private Optional<String> candidateName() {
		return fu.scan(folder, FAMILY, OWN).candidate().map(o -> o.file().getFileName().toString());
	}

	@Test
	void newestValidWins() throws IOException {
		msix("a-1.1.0-2.msix", "1.1.0.2");
		msix("a-1.1.0-3.msix", "1.1.0.3");
		msix("a-1.1.0-0.msix", "1.1.0.0");
		FolderUpdates.Scan s = fu.scan(folder, FAMILY, OWN);
		assertEquals("a-1.1.0-3.msix", s.candidate().orElseThrow().file().getFileName().toString());
		assertEquals("1.1.0 (Rev. 3)", s.candidate().orElseThrow().version().display());
		assertEquals(Optional.of(new AppVersion("1.1.0", 3)), s.highestValidOwn());
	}

	@Test
	void noCandidateWhenNothingNewer() throws IOException {
		msix("a.msix", "1.1.0.1");
		FolderUpdates.Scan s = fu.scan(folder, FAMILY, OWN);
		assertEquals(Optional.empty(), s.candidate());
		assertEquals(Optional.of(OWN), s.highestValidOwn());
	}

	@Test
	void versionComesFromManifestOnly() throws IOException {
		msix("ecv-karten-9.9.9.x64.msix", "1.1.0.2");
		assertEquals("1.1.0 (Rev. 2)", fu.scan(folder, FAMILY, OWN).candidate().orElseThrow().version().display());
	}

	@Test
	void descriptionMustMatch() throws IOException {
		Path a = msix("a.msix", "1.1.0.2");
		Files.write(updates.resolve("a.msix.json"), FolderUpdates.descriptionJson("1.1.0.2", FAMILY, "00".repeat(32), Files.size(a)));
		assertEquals(Optional.empty(), candidateName(), "falsche Prüfsumme");
		Files.write(updates.resolve("a.msix.json"),
				FolderUpdates.descriptionJson("1.1.0.2", FAMILY, UpdateFiles.sha256Hex(a), Files.size(a) + 1));
		assertEquals(Optional.empty(), candidateName(), "falsche Größe");
		Files.write(updates.resolve("a.msix.json"),
				FolderUpdates.descriptionJson("9.9.9.0", FAMILY, UpdateFiles.sha256Hex(a), Files.size(a)));
		assertEquals(Optional.empty(), candidateName(), "falsche Version");
		Files.write(updates.resolve("a.msix.json"),
				FolderUpdates.descriptionJson("1.1.0.2", "Andere_x", UpdateFiles.sha256Hex(a), Files.size(a)));
		assertEquals(Optional.empty(), candidateName(), "falsche Familie");
		describe(a, "1.1.0.2");
		assertEquals(Optional.of("a.msix"), candidateName());
	}

	@Test
	void handDroppedNeedsCompleteZip() throws IOException {
		Path a = msix("a.msix", "1.1.0.2");
		assertEquals(Optional.of("a.msix"), candidateName());
		TestMsix.flipPayloadByte(a);
		Files.setLastModifiedTime(a, FileTime.from(NOW.plusSeconds(5)));
		assertEquals(Optional.empty(), candidateName());
	}

	@Test
	void truncatedZipIsNotACandidate() throws IOException {
		Path a = msix("a.msix", "1.1.0.2");
		try (RandomAccessFile raf = new RandomAccessFile(a.toFile(), "rw")) {
			raf.setLength(raf.length() - 30);
		}
		assertEquals(Optional.empty(), candidateName());
	}

	@Test
	void otherFamilyGivesReinstallHint() throws IOException {
		TestMsix.write(updates.resolve("b.msix"), TestMsix.manifestWithName("Andere", "1.2.0.0"), TestMsix.standardExtras());
		FolderUpdates.Scan s = fu.scan(folder, FAMILY, OWN);
		assertEquals(Optional.empty(), s.candidate());
		assertEquals(Optional.of(new AppVersion("1.2.0", 0)), s.otherFamilyNewer());
	}

	@Test
	void ignoresTmpClaimsAndConflictCopies() throws IOException {
		Path a = msix("a.msix", "1.1.0.2");
		Files.copy(a, updates.resolve("~karv-x-" + java.util.UUID.randomUUID() + ".tmp"));
		Files.copy(a, updates.resolve(".a.msix.~1234"));
		Files.copy(a, updates.resolve("b (conflicted copy 2026-11-10 120000).msix"));
		Files.delete(a);
		Files.writeString(updates.resolve("laden-1.1.0.2-x.json"), "{}");
		assertEquals(Optional.empty(), candidateName());
	}

	@Test
	void sameVersionTwiceTakesSmallestName() throws IOException {
		msix("b.msix", "1.1.0.2");
		msix("a.msix", "1.1.0.2");
		assertEquals(Optional.of("a.msix"), candidateName());
	}

	@Test
	void oversizedIsSkippedWithoutReading() throws IOException {
		Path big = updates.resolve("big.msix");
		try (RandomAccessFile raf = new RandomAccessFile(big.toFile(), "rw")) {
			raf.setLength(MsixManifest.MAX_MSIX_BYTES + 1);
		}
		assertEquals(Optional.empty(), candidateName());
		assertEquals(0, fu.fullChecks());
	}

	@Test
	void checkIsMemoizedByKey() throws IOException {
		msix("a.msix", "1.1.0.2");
		candidateName();
		int after = fu.fullChecks();
		candidateName();
		assertEquals(after, fu.fullChecks());
		Files.setLastModifiedTime(updates.resolve("a.msix"), FileTime.from(NOW.plusSeconds(9)));
		candidateName();
		assertEquals(after + 1, fu.fullChecks());
	}

	@Test
	void firstSeenStaysWhenDescriptionArrivesLater() throws IOException {
		Path a = msix("a.msix", "1.1.0.2");
		assertEquals(NOW, fu.scan(folder, FAMILY, OWN).candidate().orElseThrow().firstSeen());
		clock.now = NOW.plus(Duration.ofMinutes(5));
		describe(a, "1.1.0.2");
		assertEquals(NOW, fu.scan(folder, FAMILY, OWN).candidate().orElseThrow().firstSeen());
	}

	@Test
	void memoDroppedForVanishedFile() throws IOException {
		Path a = msix("a.msix", "1.1.0.2");
		candidateName();
		Files.delete(a);
		assertEquals(Optional.empty(), candidateName());
		clock.now = NOW.plus(Duration.ofMinutes(5));
		msix("a.msix", "1.1.0.2");
		Files.setLastModifiedTime(a, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
		assertEquals(NOW.plus(Duration.ofMinutes(5)), fu.scan(folder, FAMILY, OWN).candidate().orElseThrow().firstSeen());
	}

	@Test
	void stillValidDetectsReplacedFile() throws IOException {
		Path a = msix("a.msix", "1.1.0.2");
		describe(a, "1.1.0.2");
		FolderOffer offer = fu.scan(folder, FAMILY, OWN).candidate().orElseThrow();
		assertTrue(offer.stillValid());
		TestMsix.flipPayloadByte(a);
		Files.setLastModifiedTime(a, FileTime.from(NOW.plusSeconds(7)));
		assertFalse(offer.stillValid());
		Files.delete(a);
		assertFalse(offer.stillValid());
	}

	@Test
	void cleanupDeletesOlderValid() throws IOException {
		Path old = msix("a-2.msix", "1.1.0.2");
		describe(old, "1.1.0.2");
		msix("a-3.msix", "1.1.0.3");
		fu.scan(folder, FAMILY, OWN);
		fu.cleanup(folder, FAMILY);
		assertFalse(Files.exists(old));
		assertFalse(Files.exists(updates.resolve("a-2.msix.json")));
		assertTrue(Files.exists(updates.resolve("a-3.msix")));
	}

	@Test
	void cleanupKeepsInvalidOlder() throws IOException {
		Path old = msix("a-2.msix", "1.1.0.2");
		TestMsix.flipPayloadByte(old);
		msix("a-3.msix", "1.1.0.3");
		fu.scan(folder, FAMILY, OWN);
		fu.cleanup(folder, FAMILY);
		assertTrue(Files.exists(old), "nur gültige ältere Installer werden gelöscht");
	}

	@Test
	void cleanupKeepsSameVersionDuplicates() throws IOException {
		msix("a.msix", "1.1.0.2");
		msix("b.msix", "1.1.0.2");
		fu.scan(folder, FAMILY, OWN);
		fu.cleanup(folder, FAMILY);
		assertTrue(Files.exists(updates.resolve("a.msix")));
		assertTrue(Files.exists(updates.resolve("b.msix")));
	}

	@Test
	void cleanupDeletesConflictCopies() throws IOException {
		Path a = msix("a.msix", "1.1.0.2");
		Files.copy(a, updates.resolve("a (conflicted copy 2026-11-10 120000).msix"));
		Files.writeString(updates.resolve("a.msix (conflicted copy 2026-11-10 120000).json"), "{}");
		Files.writeString(updates.resolve("notiz (conflicted copy 2026-11-10 120000).txt"), "x");
		fu.scan(folder, FAMILY, OWN);
		fu.cleanup(folder, FAMILY);
		assertFalse(Files.exists(updates.resolve("a (conflicted copy 2026-11-10 120000).msix")));
		assertFalse(Files.exists(updates.resolve("a.msix (conflicted copy 2026-11-10 120000).json")));
		assertTrue(Files.exists(updates.resolve("notiz (conflicted copy 2026-11-10 120000).txt")));
		assertTrue(Files.exists(a));
	}

	@Test
	void cleanupOwnOldTmpButNotThisRun() throws IOException {
		Path mine = Files.writeString(updates.resolve(UpdatesDir.tempName(ME)), "x");
		Path earlier = Files.writeString(updates.resolve(UpdatesDir.tempName("alte-id")), "x");
		Path running = Files.writeString(updates.resolve(UpdatesDir.tempName(ME)), "x");
		own.add(running);
		fu.cleanup(folder, FAMILY);
		assertFalse(Files.exists(mine));
		assertFalse(Files.exists(earlier));
		assertTrue(Files.exists(running));
	}

	@Test
	void cleanupKeepsForeignTmp() throws IOException {
		Path foreign = Files.writeString(updates.resolve(UpdatesDir.tempName("bbbb2222")), "x");
		fu.cleanup(folder, FAMILY);
		assertTrue(Files.exists(foreign));
	}

	@Test
	void cleanupOrphanDescriptionAfterOneHourSeen() throws IOException {
		Path orphan = Files.writeString(updates.resolve("x.msix.json"), "{}");
		fu.scan(folder, FAMILY, OWN);
		fu.cleanup(folder, FAMILY);
		assertTrue(Files.exists(orphan));
		clock.now = NOW.plus(Duration.ofMinutes(61));
		fu.scan(folder, FAMILY, OWN);
		fu.cleanup(folder, FAMILY);
		assertFalse(Files.exists(orphan));
	}

	@Test
	void cleanupOrphanDescriptionOlderThanOneDay() throws IOException {
		Path orphan = Files.writeString(updates.resolve("x.msix.json"), "{}");
		Files.setLastModifiedTime(orphan, FileTime.from(NOW.minus(Duration.ofHours(25))));
		fu.scan(folder, FAMILY, OWN);
		fu.cleanup(folder, FAMILY);
		assertFalse(Files.exists(orphan));
	}

	@Test
	void cleanupOnlyOwnFamily() throws IOException {
		Path other = updates.resolve("b-1.msix");
		TestMsix.write(other, TestMsix.manifestWithName("Andere", "1.1.0.2"), TestMsix.standardExtras());
		TestMsix.write(updates.resolve("b-2.msix"), TestMsix.manifestWithName("Andere", "1.1.0.3"), TestMsix.standardExtras());
		fu.scan(folder, FAMILY, OWN);
		fu.cleanup(folder, FAMILY);
		assertTrue(Files.exists(other));
	}

	@Test
	void missingUpdatesFolderGivesEmptyScan() throws IOException {
		Files.delete(updates);
		FolderUpdates.Scan s = fu.scan(folder, FAMILY, OWN);
		assertEquals(Optional.empty(), s.candidate());
		fu.cleanup(folder, FAMILY);
		assertFalse(Files.exists(updates));
	}
}
