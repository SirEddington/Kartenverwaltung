package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
	/** Die frühere ID dieses Rechners; sie galt bis zwischen {@link #V1} und {@link #V2}. */
	private static final String PREVIOUS = "maschine-0";
	private static final Ownership OWN = new Ownership(ME,
			List.of(new Ownership.Previous(PREVIOUS, Instant.ofEpochMilli(1_500_000L))));

	private static final UnitKey E1 = UnitKey.event("e1");
	private static final UnitKey E2 = UnitKey.event("e2");
	private static final UnitKey HALL = UnitKey.hall("h1");

	private static final VersionId V1 = VersionId.create(1_000_000L, "aaaaaaaa", 0);
	private static final VersionId V2 = VersionId.create(2_000_000L, "aaaaaaaa", 1);
	private static final VersionId FOREIGN = VersionId.create(1_500_000L, "bbbbbbbb", 0);
	/** Ein Aufrufer, der jede Einheit schon kennt. */
	private static final Predicate<UnitKey> KNOWN = unit -> true;
	/** Ein Aufrufer, der mit dem Teilergebnis eines Abbruchs nichts anfängt. */
	private static final Consumer<FullSyncResult> NO_ABORT = partial -> {
	};

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
		return FullSync.run(local, shared, upload, OWN, files, CLOCK);
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
	void localUnitsTheCallerDoesNotKnowCountAsChanged() throws IOException {
		Version a = version(ME, V1);
		byte[] bytes = put(local, E1, a);
		shared.writeVersion(E1, V1, bytes);
		put(local, HALL, version(ME, V2));
		// ein Event-Ordner ohne eigene Dateien, nur mit einer Vorstellung darunter (im gemeinsamen Ordner): nichts aufzunehmen
		put(shared, UnitKey.presentation("e2", "p1"), version(OTHER, FOREIGN));
		UnitKey onlyFolder = E2;
		List<UnitKey> asked = new ArrayList<>();

		FullSyncResult result = FullSync.syncUnits(local, shared, List.of(E1, HALL, onlyFolder), false, OWN, files, CLOCK,
				unit -> {
					asked.add(unit);
					return unit.equals(HALL);
				}, claim -> {
				}, NO_ABORT);

		assertEquals(Set.of(E1), result.changed(), "unbekannt und lokal: aufnehmen, obwohl nichts zu kopieren war");
		assertEquals(List.of(E1, HALL), asked, "ohne lokale Dateien wird nicht gefragt");
	}

	@Test
	void unknownLocalUnitIsInThePartialResultWhenItsSharedScanFails() throws IOException {
		put(local, E1, version(ME, V1));
		FaultyFileLayer faulty = new FaultyFileLayer(files);
		faulty.failWhen(p -> p.toString().replace('\\', '/').endsWith("shared/data/events/e1"), "listNamesWithTemps",
				new IOException("Störung"));
		VersionStore faultyShared = new VersionStore(shared.dataRoot(), faulty);
		AtomicReference<FullSyncResult> partial = new AtomicReference<>();

		assertThrows(IOException.class,
				() -> FullSync.syncUnits(local, faultyShared, List.of(E1), false, OWN, files, CLOCK, unit -> false, claim -> {
				}, partial::set));

		assertEquals(Set.of(E1), partial.get().changed());
	}

	@Test
	void claimsFlowOnlyInward() throws IOException {
		Version foreign = version(OTHER, FOREIGN);
		put(shared, E2, foreign);
		byte[] sharedClaim = claim(shared, E2, foreign, AT);
		// ein fremder lokaler Anspruch; eigene, die im gemeinsamen Ordner fehlen, lädt der Abgleich hoch (M4)
		Version mine = version(OTHER, V1);
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

		FullSyncResult result = FullSync.run(local, shared, true, OWN, files, CLOCK, seen::add);

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

		assertThrows(IOException.class, () -> FullSync.run(local, faultyShared, true, OWN, files, CLOCK, seen::add));

		assertEquals(List.of(new FullSyncResult.UploadedClaim(E1, "root", V1)), seen);
	}

	@Test
	void abortAfterASettleCarriesThePartialResult() throws IOException {
		// E1: der lokale Anspruch verliert gegen den gemeinsamen; danach scheitert das Hochladen des Anspruchs auf HALL
		Version mine = version(ME, V1);
		put(local, E1, mine);
		claim(local, E1, mine, AT);
		Version foreign = version(OTHER, FOREIGN);
		put(shared, E1, foreign);
		claim(shared, E1, foreign, AT);
		Version hall = version(ME, V2);
		put(local, HALL, hall);
		claim(local, HALL, hall, AT);
		FaultyFileLayer faulty = new FaultyFileLayer(files);
		IllegalStateException cause = new IllegalStateException("Netz weg");
		faulty.failNextUnchecked(p -> p.toString().replace('\\', '/').contains("shared/data/halls/h1/claims"), "createNew",
				cause);
		VersionStore faultyShared = new VersionStore(shared.dataRoot(), faulty);
		List<UnitKey> units = List.of(E1, HALL);
		AtomicReference<FullSyncResult> partial = new AtomicReference<>();

		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> FullSync.syncUnits(local, faultyShared, units, true, OWN, files, CLOCK, KNOWN, claim -> {
				}, partial::set));

		assertSame(cause, thrown);
		assertEquals(List.of(new LostVersion(E1, List.of(V1), FOREIGN)), partial.get().lost());
		assertEquals(Set.of(E1), partial.get().changed());
		// die Wiederholung findet auf E1 beide Ansprüche gleich: nichts mehr zu melden, nur HALL wird nachgeholt
		FullSyncResult again = FullSync.syncUnits(local, shared, units, true, OWN, files, CLOCK, KNOWN, claim -> {
		}, NO_ABORT);
		assertTrue(again.lost().isEmpty());
		assertTrue(again.changed().isEmpty());
		assertEquals(List.of(new FullSyncResult.UploadedClaim(HALL, "root", V2)), again.uploaded());
	}

	@Test
	void abortRightAfterReplacingTheLocalClaimStillCarriesTheLoss() throws IOException {
		Version mine = version(ME, V1);
		put(local, E1, mine);
		claim(local, E1, mine, AT);
		Version foreign = version(OTHER, FOREIGN);
		put(shared, E1, foreign);
		claim(shared, E1, foreign, AT);
		// sobald der lokale Anspruch ersetzt ist, scheitert das nächste lokale Lesen (das Bestimmen der verlorenen Strecke)
		FaultyFileLayer faulty = new FaultyFileLayer(files);
		faulty.onEach("replaceDamaged", () -> faulty.failNextUnchecked("read", new IllegalStateException("Platte weg")));
		VersionStore faultyLocal = new VersionStore(local.dataRoot(), faulty);
		AtomicReference<FullSyncResult> partial = new AtomicReference<>();

		assertThrows(IllegalStateException.class,
				() -> FullSync.syncUnits(faultyLocal, shared, List.of(E1), true, OWN, faulty, CLOCK, KNOWN, claim -> {
				}, partial::set));

		assertEquals(List.of(new LostVersion(E1, List.of(V1), FOREIGN)), partial.get().lost());
		assertEquals(Set.of(E1), partial.get().changed());
		assertEquals(FOREIGN, StoreCodec.readClaim(local.readClaimBytes(E1, "root").orElseThrow()).version());
	}

	@Test
	void abortWithAnIOExceptionCarriesItAsTheCause() throws IOException {
		Version hall = version(ME, V2);
		put(local, HALL, hall);
		claim(local, HALL, hall, AT);
		FaultyFileLayer faulty = new FaultyFileLayer(files);
		IOException cause = new IOException("Störung");
		faulty.failWhen(p -> p.toString().replace('\\', '/').contains("shared/data/halls/h1/claims"), "createNew", cause);
		VersionStore faultyShared = new VersionStore(shared.dataRoot(), faulty);
		AtomicReference<FullSyncResult> partial = new AtomicReference<>();

		IOException thrown = assertThrows(IOException.class,
				() -> FullSync.syncUnits(local, faultyShared, List.of(HALL), true, OWN, files, CLOCK, KNOWN, claim -> {
				}, partial::set));

		assertSame(cause, thrown);
		assertTrue(partial.get().lost().isEmpty());
		assertTrue(partial.get().changed().isEmpty());
	}

	@Test
	void failingAbortCallbackIsSuppressedOnTheOriginalFailure() throws IOException {
		Version hall = version(ME, V2);
		put(local, HALL, hall);
		claim(local, HALL, hall, AT);
		FaultyFileLayer faulty = new FaultyFileLayer(files);
		IOException cause = new IOException("Störung");
		faulty.failWhen(p -> p.toString().replace('\\', '/').contains("shared/data/halls/h1/claims"), "createNew", cause);
		VersionStore faultyShared = new VersionStore(shared.dataRoot(), faulty);
		IllegalStateException callbackFailure = new IllegalStateException("Melden gescheitert");

		IOException thrown = assertThrows(IOException.class,
				() -> FullSync.syncUnits(local, faultyShared, List.of(HALL), true, OWN, files, CLOCK, KNOWN, claim -> {
				}, partial -> {
					throw callbackFailure;
				}));

		assertSame(cause, thrown);
		assertEquals(List.of(callbackFailure), List.of(thrown.getSuppressed()));
	}

	@Test
	void abortCallbackIsNotCalledWithoutAFailure() throws IOException {
		put(local, E1, version(ME, V1));
		AtomicBoolean called = new AtomicBoolean();

		FullSync.syncUnits(local, shared, List.of(E1), false, OWN, files, CLOCK, KNOWN, claim -> {
		}, partial -> called.set(true));

		assertFalse(called.get());
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
				ChainResolver.resolve(shared.readUnit(E1), OWN).status());
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
	void ownClaimsOfAPreviousIdAreUploadedOnlyUntilTheChange() throws IOException {
		// V1 entstand vor der Neuvergabe (eigen), V2 danach unter derselben alten ID (die Kopie: fremd)
		Version before = version(PREVIOUS, V1);
		put(local, E1, before);
		claim(local, E1, before, AT);
		Version after = version(PREVIOUS, V2);
		put(local, E2, after);
		claim(local, E2, after, AT);

		FullSyncResult result = run(false);

		assertTrue(shared.readClaimBytes(E1, "root").isPresent());
		assertTrue(shared.readClaimBytes(E2, "root").isEmpty());
		assertEquals(List.of(new FullSyncResult.UploadedClaim(E1, "root", V1)), result.uploaded());
	}

	@Test
	void ownClaimOfAPreviousIdIsComparedForALateDefeat() throws IOException {
		Version mine = version(PREVIOUS, V1);
		byte[] mineBytes = put(local, E1, mine);
		claim(local, E1, mine, AT);
		shared.writeVersion(E1, V1, mineBytes);
		Version foreign = version(OTHER, FOREIGN);
		put(shared, E1, foreign);
		claim(shared, E1, foreign, AT);

		FullSyncResult result = run(false);

		assertEquals(List.of(new FeedPoll.Contested(E1, "root")), result.contested());
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

	@Test
	void ownClaimMissingInSharedIsUploadedWithoutMerge() throws IOException {
		// der gemeinsame Ordner wurde geleert: nur lokal liegen die eigenen Versionen und Ansprüche
		Version mine = version(ME, V1);
		put(local, E1, mine);
		byte[] rootClaim = claim(local, E1, mine, AT);
		Version second = version(ME, V2, V1);
		put(local, E1, second);
		byte[] secondClaim = claim(local, E1, second, AT);
		List<FullSyncResult.UploadedClaim> seen = new ArrayList<>();

		FullSyncResult result = FullSync.run(local, shared, false, OWN, files, CLOCK, seen::add);

		assertArrayEquals(rootClaim, shared.readClaimBytes(E1, "root").orElseThrow());
		assertArrayEquals(secondClaim, shared.readClaimBytes(E1, V1.value()).orElseThrow());
		assertTrue(has(shared, "events/e1/" + V1 + ".json"));
		assertTrue(has(shared, "events/e1/" + V2 + ".json"));
		List<FullSyncResult.UploadedClaim> expected = List.of(new FullSyncResult.UploadedClaim(E1, "root", V1),
				new FullSyncResult.UploadedClaim(E1, V1.value(), V2));
		assertEquals(expected, result.uploaded());
		assertEquals(expected, seen);
		assertTrue(result.lost().isEmpty());
		assertTrue(result.contested().isEmpty());
		assertTrue(result.changed().isEmpty());
		UnitState state = ChainResolver.resolve(shared.readUnit(E1), new Ownership(OTHER, List.of()));
		assertEquals(List.of(mine, second), state.chain());
	}

	@Test
	void ownClaimMissingAfterRollbackIsUploaded() throws IOException {
		Version mine = version(ME, V1);
		put(local, E1, mine);
		byte[] rootClaim = claim(local, E1, mine, AT);
		Version second = version(ME, V2, V1);
		put(local, E1, second);
		byte[] secondClaim = claim(local, E1, second, AT);
		// der gemeinsame Ordner ist auf einen älteren Stand zurückgespielt: nur die erste Version und ihr Anspruch
		put(shared, E1, mine);
		shared.createClaim(E1, "root", rootClaim);

		FullSyncResult result = run(false);

		assertEquals(List.of(new FullSyncResult.UploadedClaim(E1, V1.value(), V2)), result.uploaded());
		assertArrayEquals(secondClaim, shared.readClaimBytes(E1, V1.value()).orElseThrow());
		assertTrue(has(shared, "events/e1/" + V2 + ".json"));
		assertTrue(result.lost().isEmpty());
		assertTrue(result.contested().isEmpty());
	}

	@Test
	void foreignLocalClaimIsNotUploadedWithoutMerge() throws IOException {
		Version foreign = version(OTHER, FOREIGN);
		put(local, E1, foreign);
		byte[] foreignClaim = claim(local, E1, foreign, AT);

		FullSyncResult result = run(false);

		assertTrue(result.uploaded().isEmpty());
		assertTrue(shared.readClaimBytes(E1, "root").isEmpty());
		// die Version kommt trotzdem hoch
		assertTrue(has(shared, "events/e1/" + FOREIGN + ".json"));
		// nur das Mitnehmen lädt fremde lokale Ansprüche hoch
		assertEquals(List.of(new FullSyncResult.UploadedClaim(E1, "root", FOREIGN)), run(true).uploaded());
		assertArrayEquals(foreignClaim, shared.readClaimBytes(E1, "root").orElseThrow());
	}

	@Test
	void ownClaimLosesToAnotherClaimThatArrivedMeanwhile() throws IOException {
		Version mine = version(ME, V1);
		put(local, E1, mine);
		byte[] oldLocalClaim = claim(local, E1, mine, AT);
		Version second = version(ME, V2, V1);
		put(local, E1, second);
		claim(local, E1, second, AT);
		Version foreign = version(OTHER, FOREIGN);
		byte[][] arrived = new byte[2][];
		// ein anderer Rechner legt seinen Anspruch an, nachdem der Abgleich die Einheit aufgelistet hat
		FileLayer racing = racing(files, shared.resolve("events/e1/claims/root.json"), () -> {
			arrived[0] = put(shared, E1, foreign);
			arrived[1] = claim(shared, E1, foreign, AT);
		});
		VersionStore racingShared = new VersionStore(shared.dataRoot(), racing);

		FullSyncResult result = FullSync.run(local, racingShared, false, OWN, files, CLOCK);

		assertEquals(List.of(new LostVersion(E1, List.of(V1, V2), FOREIGN)), result.lost());
		assertEquals(Set.of(E1), result.changed());
		assertTrue(result.uploaded().isEmpty());
		assertTrue(result.contested().isEmpty());
		// der gemeinsame Anspruch gilt jetzt auch lokal, der eigene liegt beiseite
		assertArrayEquals(arrived[1], shared.readClaimBytes(E1, "root").orElseThrow());
		assertArrayEquals(arrived[1], local.readClaimBytes(E1, "root").orElseThrow());
		assertArrayEquals(arrived[0], local.readVersionBytes(E1, FOREIGN).orElseThrow());
		Path aside = local.dataRoot().resolve("events/e1/claims/root.json.verdraengt-2026-10-26_10-00-00");
		assertArrayEquals(oldLocalClaim, Files.readAllBytes(aside));
		// der Anspruch hinter der verlorenen Version geht nicht in die Kette des Gewinners
		assertTrue(shared.readClaimBytes(E1, V1.value()).isEmpty());
	}

	@Test
	void ownClaimOnALostStretchIsNotUploaded() throws IOException {
		// V1 hat den Platz an FOREIGN verloren (der lokale Anspruch ist schon ersetzt); V2 baut auf V1 auf
		Version foreign = version(OTHER, FOREIGN);
		put(shared, E1, foreign);
		byte[] foreignClaim = claim(shared, E1, foreign, AT);
		put(local, E1, foreign);
		local.createClaim(E1, "root", foreignClaim);
		put(local, E1, version(ME, V1));
		Version second = version(ME, V2, V1);
		put(local, E1, second);
		claim(local, E1, second, AT);

		FullSyncResult result = run(false);

		assertTrue(shared.readClaimBytes(E1, V1.value()).isEmpty());
		assertTrue(result.uploaded().isEmpty());
		assertTrue(result.contested().isEmpty());
		assertTrue(result.lost().isEmpty());
		assertTrue(has(shared, "events/e1/" + V2 + ".json"));
	}

	@Test
	void contestedUnitUploadsNoOwnClaim() throws IOException {
		Version mine = version(ME, V1);
		put(local, E1, mine);
		claim(local, E1, mine, AT);
		Version second = version(ME, V2, V1);
		put(local, E1, second);
		claim(local, E1, second, AT);
		Version foreign = version(OTHER, FOREIGN);
		put(shared, E1, foreign);
		claim(shared, E1, foreign, AT);

		FullSyncResult result = run(false);

		assertEquals(List.of(new FeedPoll.Contested(E1, "root")), result.contested());
		assertTrue(result.uploaded().isEmpty());
		assertTrue(shared.readClaimBytes(E1, V1.value()).isEmpty());
	}

	@Test
	void syncUnitsOfAllUnitsEqualsRun() throws IOException {
		UnitKey p1 = UnitKey.presentation("e1", "p1");
		UnitKey p2 = UnitKey.presentation("e1", "p2");
		UnitKey booking = UnitKey.unassignedBooking("e1", "p2", "b1");
		// eigener Anspruch nur lokal (wird hochgeladen), eigene Version ohne Anspruch nur lokal
		Version mine = version(ME, V1);
		put(local, E1, mine);
		claim(local, E1, mine, AT);
		put(local, p1, version(ME, V2));
		// fremder Anspruch nur im gemeinsamen Ordner (kommt herein), Einheiten nur dort
		Version foreign = version(OTHER, FOREIGN);
		put(shared, E2, foreign);
		claim(shared, E2, foreign, AT);
		put(shared, booking, foreign);
		claim(shared, booking, foreign, AT);
		// umstrittener Platz in der Halle
		Version hall = version(ME, V2);
		put(local, HALL, hall);
		claim(local, HALL, hall, AT);
		put(shared, HALL, foreign);
		claim(shared, HALL, foreign, AT);
		Path copy = dir.resolve("kopie");
		copyTree(dir.resolve("local"), copy.resolve("local"));
		copyTree(dir.resolve("shared"), copy.resolve("shared"));
		VersionStore localCopy = new VersionStore(copy.resolve("local").resolve("data"), files);
		VersionStore sharedCopy = new VersionStore(copy.resolve("shared").resolve("data"), files);
		List<UnitKey> units = List.of(E1, p1, p2, booking, E2, HALL);
		List<FullSyncResult.UploadedClaim> seen = new ArrayList<>();

		FullSyncResult expected = run(false);
		FullSyncResult actual = FullSync.syncUnits(localCopy, sharedCopy, units, false, OWN, files, CLOCK, KNOWN, seen::add,
				NO_ABORT);

		assertEquals(expected, actual);
		assertEquals(actual.uploaded(), seen);
		assertEquals(List.of(new FullSyncResult.UploadedClaim(E1, "root", V1)), actual.uploaded());
		assertEquals(List.of(new FeedPoll.Contested(HALL, "root")), actual.contested());
		assertEquals(Set.of(E2, booking, HALL), actual.changed());
		assertEquals(contents(local.dataRoot()), contents(localCopy.dataRoot()));
		assertEquals(contents(shared.dataRoot()), contents(sharedCopy.dataRoot()));
	}

	/** Eine Aktion mit Dateizugriff. */
	private interface IoAction {
		void run() throws IOException;
	}

	/**
	 * Eine Dateischicht, die unmittelbar vor dem ersten Anlegen von {@code target} {@code before} ausführt: Ein anderer
	 * Rechner war zwischen Auflisten und Anlegen schneller.
	 */
	private static FileLayer racing(FileLayer delegate, Path target, IoAction before) {
		AtomicBoolean done = new AtomicBoolean();
		return (FileLayer) Proxy.newProxyInstance(FileLayer.class.getClassLoader(), new Class<?>[] { FileLayer.class },
				(proxy, method, args) -> {
					if (method.getName().equals("createNew") && target.equals(args[0]) && done.compareAndSet(false, true)) {
						before.run();
					}
					try {
						return method.invoke(delegate, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}

	private static void copyTree(Path from, Path to) throws IOException {
		try (Stream<Path> walk = Files.walk(from)) {
			for (Path source : walk.toList()) {
				Path target = to.resolve(from.relativize(source).toString());
				if (Files.isDirectory(source)) {
					Files.createDirectories(target);
				} else {
					Files.copy(source, target);
				}
			}
		}
	}

	/** Alle Dateien unter {@code root} mit ihrem Inhalt. */
	private static Map<String, String> contents(Path root) throws IOException {
		Map<String, String> result = new TreeMap<>();
		for (String rel : tree(root)) {
			result.put(rel, new String(Files.readAllBytes(root.resolve(rel)), StandardCharsets.UTF_8));
		}
		return result;
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
