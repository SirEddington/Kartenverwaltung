package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class FullSyncTest {

	private static final Instant AT = Instant.parse("2026-10-26T10:00:00Z");
	private static final Instant LATER = Instant.parse("2026-10-26T11:00:00Z");
	private static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);
	private static final String ME = "maschine-1";
	private static final String OTHER = "maschine-2";

	private static final UnitKey E1 = UnitKey.event("e1");
	private static final UnitKey E2 = UnitKey.event("e2");
	private static final UnitKey HALL = UnitKey.hall("h1");

	private static final VersionId V1 = VersionId.create(1_000_000L, "aaaaaaaa", 0);
	private static final VersionId V2 = VersionId.create(2_000_000L, "aaaaaaaa", 1);
	private static final VersionId FOREIGN = VersionId.create(1_500_000L, "bbbbbbbb", 0);

	@TempDir
	Path dir;

	private FileLayer files;
	private VersionStore local;
	private VersionStore shared;

	@BeforeEach
	void setUp() {
		files = new NioFileLayer();
		local = new VersionStore(dir.resolve("local").resolve("data"), files);
		shared = new VersionStore(dir.resolve("shared").resolve("data"), files);
	}

	private static Version version(String machine, VersionId id, VersionId... parents) {
		return new Version(1, id, List.of(parents), machine, "Kasse", "Anna", AT, "save-" + id, false,
				new ObjectMapper().createObjectNode().put("name", "Sitzung"));
	}

	/** Legt die Versionsdatei an und liefert ihre Bytes. */
	private static byte[] put(VersionStore store, UnitKey unit, Version version) throws IOException {
		byte[] bytes = StoreCodec.write(version);
		store.writeVersion(unit, version.id(), bytes);
		return bytes;
	}

	/** Legt die Anspruchsdatei der Version an (die Versionsdatei muss schon da sein) und liefert ihre Bytes. */
	private static byte[] claim(VersionStore store, UnitKey unit, Version version, Instant at) throws IOException {
		byte[] versionBytes = store.readVersionBytes(unit, version.id()).orElseThrow();
		byte[] bytes = StoreCodec.write(new Claim(version.id(), StoreCodec.sha256(versionBytes), version.machine(), at));
		store.createClaim(unit, version.claimName(), bytes);
		return bytes;
	}

	private FullSyncResult run(boolean upload) throws IOException {
		return FullSync.run(local, shared, upload, ME, files, CLOCK);
	}

	private static boolean has(VersionStore store, String rel) throws IOException {
		return store.readRelative(rel).isPresent();
	}

	@Test
	void versionsFlowBothWays() throws IOException {
		Version a = version(ME, V1);
		Version b = version(OTHER, FOREIGN);
		byte[] aBytes = put(local, E1, a);
		byte[] bBytes = put(shared, E2, b);
		put(local, HALL, version(ME, V2));

		FullSyncResult result = run(false);

		assertArrayEquals(aBytes, shared.readVersionBytes(E1, V1).orElseThrow());
		assertArrayEquals(bBytes, local.readVersionBytes(E2, FOREIGN).orElseThrow());
		assertTrue(has(shared, "halls/h1/" + V2 + ".json"));
		assertEquals(Set.of(E2), result.changed());
		assertTrue(result.lost().isEmpty());
		assertEquals(local.allDataFiles(), shared.allDataFiles());
	}

	@Test
	void existingFilesAreNotComparedOrOverwritten() throws IOException {
		put(local, E1, version(ME, V1));
		byte[] sharedBytes = StoreCodec.write(version(OTHER, V1));
		shared.writeVersion(E1, V1, sharedBytes);
		byte[] localBytes = local.readVersionBytes(E1, V1).orElseThrow();

		FullSyncResult result = run(true);

		assertArrayEquals(localBytes, local.readVersionBytes(E1, V1).orElseThrow());
		assertArrayEquals(sharedBytes, shared.readVersionBytes(E1, V1).orElseThrow());
		assertTrue(result.changed().isEmpty());
		assertTrue(result.lost().isEmpty());
	}

	@Test
	void claimsFlowOnlyInward() throws IOException {
		Version foreign = version(OTHER, FOREIGN);
		put(shared, E2, foreign);
		byte[] sharedClaim = claim(shared, E2, foreign, AT);
		Version mine = version(ME, V1);
		put(local, E1, mine);
		claim(local, E1, mine, AT);

		FullSyncResult result = run(false);

		assertArrayEquals(sharedClaim, local.readClaimBytes(E2, "root").orElseThrow());
		assertTrue(shared.readClaimBytes(E1, "root").isEmpty());
		assertEquals(Set.of(E2), result.changed());
		assertTrue(result.lost().isEmpty());
		// die Version des lokalen Anspruchs wird trotzdem hochgeladen
		assertTrue(has(shared, "events/e1/" + V1 + ".json"));
	}

	@Test
	void mergeUploadsLocalClaims() throws IOException {
		Version mine = version(ME, V1);
		put(local, E1, mine);
		byte[] localClaim = claim(local, E1, mine, AT);
		Version second = version(ME, V2, V1);
		put(local, E1, second);
		byte[] secondClaim = claim(local, E1, second, AT);

		FullSyncResult result = run(true);

		assertArrayEquals(localClaim, shared.readClaimBytes(E1, "root").orElseThrow());
		assertArrayEquals(secondClaim, shared.readClaimBytes(E1, V1.value()).orElseThrow());
		assertTrue(has(shared, "events/e1/" + V1 + ".json"));
		assertTrue(has(shared, "events/e1/" + V2 + ".json"));
		assertTrue(result.changed().isEmpty());
		assertTrue(result.lost().isEmpty());
	}

	@Test
	void ownClaimsOnBothSidesAreComparedWithoutUploading() throws IOException {
		// E1: der eigene lokale Anspruch, im gemeinsamen Ordner hat ein anderer den Platz
		Version mine = version(ME, V1);
		put(local, E1, mine);
		byte[] localClaim = claim(local, E1, mine, AT);
		Version foreign = version(OTHER, FOREIGN);
		put(shared, E1, foreign);
		claim(shared, E1, foreign, AT);
		// E2: der lokale Anspruch ist nicht der eigene; ihn klärt die Änderungsliste, nicht der Abgleich
		Version theirs = version(OTHER, V2);
		put(local, E2, theirs);
		claim(local, E2, theirs, AT);
		put(shared, E2, foreign);
		claim(shared, E2, foreign, AT);
		// HALL: gleicher Anspruch auf beiden Seiten
		Version same = version(ME, V2);
		put(local, HALL, same);
		byte[] sameClaim = claim(local, HALL, same, AT);
		put(shared, HALL, same);
		shared.createClaim(HALL, "root", sameClaim);

		FullSyncResult result = run(false);

		assertEquals(List.of(new FeedPoll.Contested(E1, "root")), result.contested());
		// ersetzt wird nichts: das entscheidet der Aufrufer
		assertArrayEquals(localClaim, local.readClaimBytes(E1, "root").orElseThrow());
		// beim Mitnehmen entscheidet der Abgleich selbst
		assertTrue(run(true).contested().isEmpty());
	}

	@Test
	void uploadedClaimsAreReportedAsTheyHappen() throws IOException {
		Version mine = version(ME, V1);
		put(local, E1, mine);
		claim(local, E1, mine, AT);
		Version hall = version(ME, V2);
		put(local, HALL, hall);
		claim(local, HALL, hall, AT);
		// E2: der Anspruch liegt schon gleich im gemeinsamen Ordner, er wird nicht hochgeladen
		Version known = version(ME, FOREIGN);
		put(local, E2, known);
		byte[] knownClaim = claim(local, E2, known, AT);
		put(shared, E2, known);
		shared.createClaim(E2, "root", knownClaim);
		List<FullSyncResult.UploadedClaim> seen = new ArrayList<>();

		FullSyncResult result = FullSync.run(local, shared, true, ME, files, CLOCK, seen::add);

		List<FullSyncResult.UploadedClaim> expected = List.of(new FullSyncResult.UploadedClaim(E1, "root", V1),
				new FullSyncResult.UploadedClaim(HALL, "root", V2));
		assertEquals(expected, result.uploaded());
		assertEquals(expected, seen);
		assertTrue(run(false).uploaded().isEmpty());
	}

	@Test
	void uploadedClaimsBeforeAnAbortAreStillReported() throws IOException {
		Version mine = version(ME, V1);
		put(local, E1, mine);
		claim(local, E1, mine, AT);
		Version hall = version(ME, V2);
		put(local, HALL, hall);
		claim(local, HALL, hall, AT);
		FaultyFileLayer faulty = new FaultyFileLayer(files);
		faulty.failWhen(p -> p.toString().replace('\\', '/').contains("shared/data/halls/h1/claims"), "createNew",
				new IOException("Störung"));
		VersionStore faultyShared = new VersionStore(shared.dataRoot(), faulty);
		List<FullSyncResult.UploadedClaim> seen = new ArrayList<>();

		assertThrows(IOException.class, () -> FullSync.run(local, faultyShared, true, ME, files, CLOCK, seen::add));

		assertEquals(List.of(new FullSyncResult.UploadedClaim(E1, "root", V1)), seen);
	}

	@Test
	void mergeWithForeignClaimReportsLost() throws IOException {
		Version mine = version(ME, V1);
		byte[] mineBytes = put(local, E1, mine);
		byte[] oldLocalClaim = claim(local, E1, mine, AT);
		Version second = version(ME, V2, V1);
		put(local, E1, second);
		claim(local, E1, second, AT);
		Version foreign = version(OTHER, FOREIGN);
		byte[] foreignBytes = put(shared, E1, foreign);
		byte[] sharedClaim = claim(shared, E1, foreign, AT);

		FullSyncResult result = run(true);

		assertEquals(List.of(new LostVersion(E1, List.of(V1, V2), FOREIGN)), result.lost());
		assertEquals(Set.of(E1), result.changed());
		// der gemeinsame Anspruch gewinnt und gilt jetzt auch lokal
		assertArrayEquals(sharedClaim, shared.readClaimBytes(E1, "root").orElseThrow());
		assertArrayEquals(sharedClaim, local.readClaimBytes(E1, "root").orElseThrow());
		assertArrayEquals(foreignBytes, local.readVersionBytes(E1, FOREIGN).orElseThrow());
		// der verdrängte lokale Anspruch bleibt unter neuem Namen liegen
		Path claimsDir = local.dataRoot().resolve("events/e1/claims");
		List<String> aside = files.listNames(claimsDir).stream().filter(n -> n.startsWith("root.json.verdraengt-")).toList();
		assertEquals(List.of("root.json.verdraengt-2026-10-26_10-00-00"), aside);
		assertArrayEquals(oldLocalClaim, Files.readAllBytes(claimsDir.resolve(aside.get(0))));
		// die eigenen Versionen bleiben erhalten, aber der Anspruch hinter V1 geht nicht in die Kette des Gewinners
		assertArrayEquals(mineBytes, shared.readVersionBytes(E1, V1).orElseThrow());
		assertTrue(shared.readClaimBytes(E1, V1.value()).isEmpty());
		assertEquals(UnitState.Status.OK,
				ChainResolver.resolve(shared.readUnit(E1), ME).status());
	}

	@Test
	void sameVersionWithDifferentBytesIsNoConflict() throws IOException {
		Version mine = version(ME, V1);
		put(local, E1, mine);
		byte[] localClaim = claim(local, E1, mine, AT);
		put(shared, E1, mine);
		byte[] sharedClaim = claim(shared, E1, mine, LATER);

		FullSyncResult result = run(true);

		assertTrue(result.lost().isEmpty());
		assertTrue(result.changed().isEmpty());
		assertArrayEquals(localClaim, local.readClaimBytes(E1, "root").orElseThrow());
		assertArrayEquals(sharedClaim, shared.readClaimBytes(E1, "root").orElseThrow());
	}

	@Test
	void unreadableClaimsAreSkipped() throws IOException {
		Version mine = version(ME, V1);
		put(local, E1, mine);
		put(shared, E1, mine);
		Path localClaim = local.resolve("events/e1/claims/root.json");
		Files.createDirectories(localClaim.getParent());
		Files.write(localClaim, "{kaputt".getBytes(StandardCharsets.UTF_8));
		Version foreign = version(OTHER, FOREIGN);
		put(shared, E2, foreign);
		Path sharedClaim = shared.resolve("events/e2/claims/root.json");
		Files.createDirectories(sharedClaim.getParent());
		Files.write(sharedClaim, "{kaputt".getBytes(StandardCharsets.UTF_8));
		byte[] sharedBytes = StoreCodec.write(new Claim(V1, StoreCodec.sha256(StoreCodec.write(mine)), ME, AT));
		shared.createClaim(E1, "root", sharedBytes);

		FullSyncResult result = run(true);

		assertTrue(result.lost().isEmpty());
		assertTrue(result.changed().contains(E2), "die Version kommt trotzdem");
		assertTrue(local.readClaimBytes(E2, "root").isEmpty(), "unlesbarer gemeinsamer Anspruch wird nicht kopiert");
		assertArrayEquals("{kaputt".getBytes(StandardCharsets.UTF_8), local.readClaimBytes(E1, "root").orElseThrow());
	}

	@Test
	void claimOnPresentationAndBookingFoldersIsCopied() throws IOException {
		UnitKey presentation = UnitKey.presentation("e1", "p1");
		UnitKey booking = UnitKey.unassignedBooking("e1", "p1", "b1");
		Version v = version(OTHER, FOREIGN);
		put(shared, presentation, v);
		byte[] claim1 = claim(shared, presentation, v, AT);
		Version w = version(OTHER, V2);
		put(shared, booking, w);
		byte[] claim2 = claim(shared, booking, w, AT);

		FullSyncResult result = run(false);

		assertArrayEquals(claim1, local.readClaimBytes(presentation, "root").orElseThrow());
		assertArrayEquals(claim2, local.readClaimBytes(booking, "root").orElseThrow());
		assertEquals(Set.of(presentation, booking), result.changed());
	}

	@Test
	void unitNamedClaimsIsNotTakenForAClaimFolder() throws IOException {
		UnitKey odd = UnitKey.booking("e1", "p1", "claims");
		Version v = version(OTHER, FOREIGN);
		byte[] bytes = put(shared, odd, v);

		FullSyncResult result = run(true);

		assertArrayEquals(bytes, local.readVersionBytes(odd, FOREIGN).orElseThrow());
		assertEquals(Set.of(odd), result.changed());
	}

	@Test
	void bothHaveUnitsNeedsVersionsOnBothSides() throws IOException {
		assertFalse(FullSync.bothHaveUnits(local, shared));
		put(local, E1, version(ME, V1));
		assertFalse(FullSync.bothHaveUnits(local, shared));
		Version foreign = version(OTHER, FOREIGN);
		put(shared, E2, foreign);
		assertTrue(FullSync.bothHaveUnits(local, shared));
	}

	@Test
	void bothHaveUnitsIgnoresClaimsAndForeignFiles() throws IOException {
		put(local, E1, version(ME, V1));
		Version foreign = version(OTHER, FOREIGN);
		// im gemeinsamen Ordner nur ein Anspruch und eine fremde Datei, keine Versionsdatei
		byte[] claimBytes = StoreCodec.write(new Claim(FOREIGN, "00", OTHER, AT));
		shared.createClaim(E2, "root", claimBytes);
		Files.write(shared.resolve("events/e2/Thumbs.db"), new byte[] { 1 });

		assertFalse(FullSync.bothHaveUnits(local, shared));
		put(shared, E2, foreign);
		assertTrue(FullSync.bothHaveUnits(local, shared));
	}

	@Test
	void setAsideRenamesAndNeverDeletes() throws IOException {
		byte[] bytes = put(local, E1, version(ME, V1));
		claim(local, E1, version(ME, V1), AT);
		List<String> before = local.allDataFiles();

		Path aside = FullSync.setAside(local.dataRoot(), files, CLOCK);

		assertEquals(local.dataRoot().resolveSibling("data-beiseite-2026-10-26_10-00-00"), aside);
		assertFalse(Files.exists(local.dataRoot()));
		VersionStore moved = new VersionStore(aside, files);
		assertEquals(before, moved.allDataFiles());
		assertArrayEquals(bytes, moved.readVersionBytes(E1, V1).orElseThrow());

		// ein zweites Mal in derselben Sekunde bekommt einen Zusatz
		put(local, E2, version(ME, V2));
		Path second = FullSync.setAside(local.dataRoot(), files, CLOCK);
		assertEquals(local.dataRoot().resolveSibling("data-beiseite-2026-10-26_10-00-00-2"), second);
		put(local, E2, version(ME, V2));
		Path third = FullSync.setAside(local.dataRoot(), files, CLOCK);
		assertEquals(local.dataRoot().resolveSibling("data-beiseite-2026-10-26_10-00-00-3"), third);
		assertTrue(Files.exists(aside));
		assertTrue(Files.exists(second));
	}

	@Test
	void setAsideWithoutDataFolderDoesNothing() throws IOException {
		assertNull(FullSync.setAside(local.dataRoot(), files, CLOCK));
		assertFalse(Files.exists(dir.resolve("local")));
	}

	@Test
	void setAsideThenSyncFillsEmptyStore() throws IOException {
		put(local, E1, version(ME, V1));
		Version foreign = version(OTHER, FOREIGN);
		put(shared, E2, foreign);

		FullSync.setAside(local.dataRoot(), files, CLOCK);
		FullSyncResult result = run(false);

		assertEquals(Set.of(E2), result.changed());
		assertEquals(List.of("events/e2/" + FOREIGN + ".json"), local.allDataFiles());
	}

	@Test
	void foreignFilesAreIgnored() throws IOException {
		put(local, E1, version(ME, V1));
		put(shared, E2, version(OTHER, FOREIGN));
		String[] stray = { "desktop.ini", "Thumbs.db", "x (conflicted copy 2026-10-26).json", "ABC.json" };
		for (VersionStore store : List.of(local, shared)) {
			for (String rel : List.of("", "events", "events/e1", "events/e2", "events/e1/claims", "events/e2/claims", "halls",
					"halls/h9")) {
				Path folder = rel.isEmpty() ? store.dataRoot() : store.resolve(rel);
				Files.createDirectories(folder);
				for (String name : stray) {
					Files.write(folder.resolve(name), new byte[] { 1, 2, 3 });
				}
				Files.write(folder.resolve("~karv-" + rel.length() + ".tmp"), new byte[] { 9 });
			}
			Files.createDirectories(store.resolve("feeds/maschine-9"));
			Files.write(store.resolve("feeds/maschine-9/1.json"), new byte[] { 4 });
		}
		Set<String> localBefore = tree(local.dataRoot());
		Set<String> sharedBefore = tree(shared.dataRoot());

		FullSyncResult result = run(true);

		Set<String> localAfter = tree(local.dataRoot());
		Set<String> sharedAfter = tree(shared.dataRoot());
		// nur die beiden echten Versionsdateien sind hinzugekommen
		Set<String> localAdded = diff(localAfter, localBefore);
		Set<String> sharedAdded = diff(sharedAfter, sharedBefore);
		assertEquals(Set.of("events/e2/" + FOREIGN + ".json"), localAdded);
		assertEquals(Set.of("events/e1/" + V1 + ".json"), sharedAdded);
		assertEquals(Set.of(E2), result.changed());
		assertTrue(result.lost().isEmpty());
	}

	private static Set<String> diff(Set<String> after, Set<String> before) {
		return after.stream().filter(s -> !before.contains(s)).collect(Collectors.toSet());
	}

	private static Set<String> tree(Path root) throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
					.collect(Collectors.toSet());
		}
	}
}
