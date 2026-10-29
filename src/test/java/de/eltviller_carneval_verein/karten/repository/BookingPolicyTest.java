package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.store.ChainStep;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ConflictInput.Deletion;
import de.eltviller_carneval_verein.karten.store.ConflictInput.Sibling;
import de.eltviller_carneval_verein.karten.store.ConflictInput.Standing;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ConflictNote.Kind;
import de.eltviller_carneval_verein.karten.store.ConflictPolicy;
import de.eltviller_carneval_verein.karten.store.Resolution;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;

/** Die Strategie für Buchungen auf JSON, ohne Versionsspeicher. */
class BookingPolicyTest {

	private static final ObjectMapper MAPPER = JsonMapperFactory.createCompact();
	private static final String OWN = "ownmach1";
	private static final String OTHER = "othermac";
	private static final String THIRD = "thirdmac";

	private static final String EVENT = "event-1";
	private static final String PRESENTATION = "pres-1";
	private static final UnitKey S1 = seat("s1");
	private static final UnitKey S2 = seat("s2");
	private static final UnitKey S3 = seat("s3");

	private static final ConflictPolicy POLICY = new BookingPolicy();

	private static int counter;

	private static UnitKey seat(String seatId) {
		return UnitKey.booking(EVENT, PRESENTATION, seatId);
	}

	private static ObjectNode json(String text) {
		try {
			return (ObjectNode) MAPPER.readTree(text);
		} catch (IOException e) {
			throw new IllegalArgumentException(e);
		}
	}

	/**
	 * Eine Buchung so, wie das Repository sie schreibt: Kunde Max Muster auf Reihe A, Tisch 1, Sitz 1, danach die
	 * Änderungen aus {@code edit}.
	 */
	private static ObjectNode booking(String id, String seatId, Consumer<Booking> edit) {
		Booking booking = new Booking();
		booking.setId(id);
		booking.setSeatId(seatId);
		booking.setRowLabel("A");
		booking.setTableNumber(1);
		booking.setSeatNumber(1);
		booking.setCategory("Parkett");
		booking.setLastName("Muster");
		booking.setFirstName("Max");
		booking.setEMail("max@example.org");
		edit.accept(booking);
		return UnitMapper.normalize(booking);
	}

	private static ObjectNode booking(String id, String seatId) {
		return booking(id, seatId, b -> {
		});
	}

	private static Version version(String machine, String saveId, ObjectNode payload) {
		return new Version(1, VersionId.create(1_800_000_000_000L + counter, machine, counter++), List.of(), machine,
				"Kasse " + machine, "Bediener " + machine, Instant.EPOCH, saveId, false, payload);
	}

	private static Version version(String machine, ObjectNode payload) {
		return version(machine, "save-" + counter, payload);
	}

	private static Version deletion(String machine, String saveId) {
		return new Version(1, VersionId.create(1_800_000_000_000L + counter, machine, counter++), List.of(), machine,
				"Kasse " + machine, "Bediener " + machine, Instant.EPOCH, saveId, true, null);
	}

	private static Version deletion(String machine) {
		return deletion(machine, "save-" + counter);
	}

	private static ConflictInput input(UnitKey unit, Version base, Version mine, Version theirs, Version... chain) {
		return new ConflictInput(unit, base, mine, theirs, List.of(chain), OWN, Map.of());
	}

	private static ConflictInput input(UnitKey unit, Version base, Version mine, Version theirs,
			Map<UnitKey, Sibling> siblings, Version... chain) {
		return new ConflictInput(unit, base, mine, theirs, List.of(chain), OWN, siblings);
	}

	/** Eine Geschwister-Einheit, deren gültige Kette mit {@code version} endet und die nur diese Version hat. */
	private static Sibling sibling(UnitKey unit, Version version) {
		return new Sibling(new UnitSnapshot(unit, version.id(), Status.OK, version.deleted(), version.payload()),
				ChainStep.of(version), Set.of(version.saveId()));
	}

	@SafeVarargs
	private static Map<UnitKey, Sibling> siblings(Map.Entry<UnitKey, Version>... entries) {
		Map<UnitKey, Sibling> siblings = new LinkedHashMap<>();
		for (Map.Entry<UnitKey, Version> entry : entries) {
			siblings.put(entry.getKey(), sibling(entry.getKey(), entry.getValue()));
		}
		return siblings;
	}

	private static ConflictNote.Party party(Version version) {
		return new ConflictNote.Party(version.machine(), version.machineName(), version.operator(), version.id());
	}

	private static Change deletionOf(UnitKey unit, Version current) {
		return new Change(unit, List.of(current.id()), null, true);
	}

	// Regel 3: Doppelverkauf -->

	@Test
	void doubleSaleKeepsTheirsAndMovesMineWithoutPlace() {
		Version mine = version(OWN, booking("m", "s1", b -> b.setPaymentStatus(PaymentStatus.CASH)));
		Version theirs = version(OTHER, booking("t", "s1", b -> b.setLastName("Schulz")));
		ObjectNode mineBefore = mine.payload().deepCopy();

		Resolution resolution = POLICY.resolve(input(S1, null, mine, theirs, theirs));

		assertFalse(resolution.deleted());
		assertEquals(theirs.payload(), resolution.payload());
		UnitKey moved = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		assertEquals(1, resolution.extra().size());
		Change extra = resolution.extra().get(0);
		assertEquals(moved, extra.unit());
		assertTrue(extra.parents().isEmpty());
		assertFalse(extra.deleted());
		ObjectNode payload = extra.payload();
		assertEquals("Muster", payload.get("lastName").asText());
		assertEquals("Max", payload.get("firstName").asText());
		assertEquals(mine.payload().get("email"), payload.get("email"));
		assertEquals("max@example.org", payload.get("email").asText());
		assertEquals("CASH", payload.get("paymentStatus").asText());
		assertEquals("Parkett", payload.get("category").asText());
		assertTrue(payload.get("unassigned").asBoolean());
		assertTrue(payload.get("seatId").isNull());
		assertEquals("", payload.get("rowLabel").asText());
		assertEquals(0, payload.get("tableNumber").asInt());
		assertEquals(0, payload.get("seatNumber").asInt());
		// die Buchung "ohne Platz" liest das Zusammensetzen dort, wohin sie geschrieben wird
		assertTrue(UnitMapper.isReadable(new UnitSnapshot(moved, mine.id(), Status.OK, false, payload)));

		ConflictNote note = resolution.note();
		assertEquals(Kind.DOUBLE_SALE, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(moved, note.moved());
		assertEquals(json("{\"seatId\":\"s1\",\"rowLabel\":\"A\",\"tableNumber\":1,\"seatNumber\":1}"), note.place());
		assertEquals(mine.id(), note.dropped());
		assertEquals(party(theirs), note.other());
		assertTrue(note.fields().isEmpty());
		// die Eingaben bleiben unverändert
		assertEquals(mineBefore, mine.payload());
	}

	@Test
	void doubleSaleOtherIsTheVersionThatIntroducedTheirBooking() {
		Version base = version(THIRD, booking("x", "s1"));
		Version deleted = deletion(THIRD);
		Version sold = version(THIRD, booking("t", "s1"));
		// Deren ist eine spätere Änderung derselben Buchung t durch einen anderen Rechner
		Version theirs = version(OTHER, booking("t", "s1", b -> b.setComment("bezahlt bar")));
		Version mine = version(OWN, booking("m", "s1"));

		Resolution resolution = POLICY.resolve(input(S1, null, mine, theirs, base, deleted, sold, theirs));

		assertEquals(Kind.DOUBLE_SALE, resolution.note().kind());
		assertEquals(party(sold), resolution.note().other());
	}

	@Test
	void doubleSaleRevivesADeletedUnitWithoutPlace() {
		// Die Buchung m stand schon einmal ohne Platz und wurde von dort auf s1 zugewiesen (_m ist gelöscht)
		UnitKey moved = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		Version earlier = deletion(OWN);
		Version mine = version(OWN, booking("m", "s1"));
		Version theirs = version(OTHER, booking("t", "s1"));

		Resolution resolution = POLICY.resolve(input(S1, null, mine, theirs, siblings(Map.entry(moved, earlier)), theirs));

		// eine neue Erstversion übersprünge die Auflösung (die Einheit hat schon Versionen): neue Version auf der Löschung
		Change extra = resolution.extra().get(0);
		assertEquals(moved, extra.unit());
		assertEquals(List.of(earlier.id()), extra.parents());
		assertFalse(extra.deleted());
		assertTrue(extra.payload().get("unassigned").asBoolean());
		assertEquals(Kind.DOUBLE_SALE, resolution.note().kind());
	}

	@Test
	void cancelThenResellAgainstEditIsDeleteWins() {
		Version base = version(THIRD, booking("x", "s1"));
		Version cancelled = deletion(OTHER);
		Version resold = version(OTHER, booking("y", "s1", b -> b.setLastName("Neu")));
		Version mine = version(OWN, booking("x", "s1", b -> b.setPaymentStatus(PaymentStatus.CARD)));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, resold, cancelled, resold));

		assertFalse(resolution.deleted());
		assertEquals(resold.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty());
		ConflictNote note = resolution.note();
		assertEquals(Kind.DELETE_WINS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(party(cancelled), note.other());
		assertEquals(mine.id(), note.dropped());
	}

	@Test
	void cancelAndResellInOneSaveAgainstEditKeepsTheCancellation() {
		// Ergebnis A: Deren storniert x und verkauft y in einem Speichervorgang (s1: x → y, keine Löschversion);
		// Meine ändert x. x lebt nirgends mehr: Die Stornierung gilt, x wird nicht als _x wiederbelebt
		Version base = version(THIRD, booking("x", "s1"));
		Version theirs = version(OTHER, booking("y", "s1"));
		Version mine = version(OWN, booking("x", "s1", b -> b.setComment("Rollstuhl")));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertFalse(resolution.deleted());
		assertEquals(theirs.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty());
		ConflictNote note = resolution.note();
		assertEquals(Kind.DELETE_WINS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(party(theirs), note.other());
		assertEquals(mine.id(), note.dropped());
	}

	@Test
	void moveAndSellInOneSaveTransfersMyEditToTheMovedCopy() {
		// Ergebnis B: Deren verschiebt x nach s2 und verkauft y auf s1 in einem Speichervorgang; Meine ändert x auf s1.
		// x bekommt die eigene Änderung auf s2, kein zweites x unter _x
		Version base = version(THIRD, booking("x", "s1"));
		Version theirs = version(OTHER, "save-theirs", booking("y", "s1"));
		Version theirCopy = version(OTHER, "save-theirs", booking("x", "s2", b -> {
			b.setSeatNumber(2);
			b.setComment("Rollstuhl");
		}));
		Version mine = version(OWN, booking("x", "s1", b -> b.setPaymentStatus(PaymentStatus.CASH)));

		Resolution resolution = POLICY.resolve(
				input(S1, base, mine, theirs, siblings(Map.entry(S2, theirCopy)), theirs));

		assertFalse(resolution.deleted());
		assertEquals(theirs.payload(), resolution.payload());
		assertNull(resolution.note());
		ObjectNode merged = booking("x", "s2", b -> {
			b.setSeatNumber(2);
			b.setComment("Rollstuhl");
			b.setPaymentStatus(PaymentStatus.CASH);
		});
		assertEquals(List.of(new Change(S2, List.of(theirCopy.id()), merged, false)), resolution.extra());
	}

	@Test
	void transferredEditWithConflictingGroupReportsIt() {
		Version base = version(THIRD, booking("x", "s1"));
		Version theirs = version(OTHER, "save-theirs", booking("y", "s1"));
		Version theirCopy = version(OTHER, "save-theirs", booking("x", "s2", b -> {
			b.setSeatNumber(2);
			b.setLastName("Schulz");
		}));
		Version mine = version(OWN, booking("x", "s1", b -> {
			b.setLastName("Meier");
			b.setComment("Abholung Freitag");
		}));

		Resolution resolution = POLICY.resolve(
				input(S1, base, mine, theirs, siblings(Map.entry(S2, theirCopy)), theirs));

		assertEquals(theirs.payload(), resolution.payload());
		// der Platz bleibt der der Kopie, der Kunde der Kopie gilt, der Kommentar kommt dazu
		ObjectNode merged = booking("x", "s2", b -> {
			b.setSeatNumber(2);
			b.setLastName("Schulz");
			b.setComment("Abholung Freitag");
		});
		assertEquals(List.of(new Change(S2, List.of(theirCopy.id()), merged, false)), resolution.extra());
		ConflictNote note = resolution.note();
		assertEquals(Kind.FIELDS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(mine.id(), note.dropped());
		assertEquals(S2, note.moved());
		assertEquals(1, note.fields().size());
		assertEquals("customer", note.fields().get(0).group());
		assertEquals("Meier", note.fields().get(0).mine().get("lastName").asText());
		assertEquals("Schulz", note.fields().get(0).theirs().get("lastName").asText());
	}

	@Test
	void myReplacementKeepsTheSeatAndCancelsTheirEditedBooking() {
		// Spiegel zu Ergebnis A: Meine ersetzt x durch y (storniert x), Deren ändert x: y bleibt auf dem Sitz
		Version base = version(THIRD, booking("x", "s1"));
		Version mine = version(OWN, booking("y", "s1", b -> b.setLastName("Neu")));
		Version theirs = version(OTHER, booking("x", "s1", b -> b.setComment("Rollstuhl")));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertFalse(resolution.deleted());
		assertEquals(mine.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty());
		ConflictNote note = resolution.note();
		assertEquals(Kind.DELETE_WINS, note.kind());
		assertEquals(OTHER, note.affected());
		assertEquals(party(mine), note.other());
		assertEquals(theirs.id(), note.dropped());
	}

	@Test
	void myMoveAndSaleTransfersTheirEditToMyCopy() {
		// Spiegel zu Ergebnis B: Meine verschiebt x nach s2 und verkauft y auf s1; Deren ändert x auf s1
		Version base = version(THIRD, booking("x", "s1"));
		Version mine = version(OWN, "save-move", booking("y", "s1"));
		Version myCopy = version(OWN, "save-move", booking("x", "s2", b -> b.setSeatNumber(2)));
		Version theirs = version(OTHER, booking("x", "s1", b -> b.setPaymentStatus(PaymentStatus.CARD)));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, siblings(Map.entry(S2, myCopy)), theirs));

		assertFalse(resolution.deleted());
		assertEquals(mine.payload(), resolution.payload());
		assertNull(resolution.note());
		ObjectNode merged = booking("x", "s2", b -> {
			b.setSeatNumber(2);
			b.setPaymentStatus(PaymentStatus.CARD);
		});
		assertEquals(List.of(new Change(S2, List.of(myCopy.id()), merged, false)), resolution.extra());
	}

	@Test
	void myMoveFollowedByAnEditOfTheNewSaleStillTransfersTheirEdit() {
		// Meine: x nach s2 verschoben und y auf s1 verkauft (save-a), danach y geändert (save-b); Deren ändert x auf s1
		Version base = version(THIRD, booking("x", "s1"));
		Version myCopy = version(OWN, "save-a", booking("x", "s2", b -> b.setSeatNumber(2)));
		Version mine = version(OWN, "save-b", booking("y", "s1", b -> b.setComment("geändert")));
		Version theirs = version(OTHER, booking("x", "s1", b -> b.setPaymentStatus(PaymentStatus.CARD)));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, siblings(Map.entry(S2, myCopy)), theirs));

		assertEquals(mine.payload(), resolution.payload());
		assertNull(resolution.note());
		ObjectNode merged = booking("x", "s2", b -> {
			b.setSeatNumber(2);
			b.setPaymentStatus(PaymentStatus.CARD);
		});
		assertEquals(List.of(new Change(S2, List.of(myCopy.id()), merged, false)), resolution.extra());
	}

	/**
	 * Meine verschiebt m von s3 nach s1 (save-a); ein anderer Rechner ändert m auf s3 und verkauft t auf s1. Meine verliert
	 * auf beiden Einheiten. Gleich in welcher Reihenfolge sie aufgelöst werden: m lebt danach in einer Einheit.
	 */
	@Test
	void lostMoveOntoADoubleSoldSeatKeepsTheBookingInEitherOrder() {
		UnitKey unassigned = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		Version s3Base = version(THIRD, booking("m", "s3", b -> b.setSeatNumber(3)));
		Version myS3Deletion = deletion(OWN, "save-a");
		Version myS1 = version(OWN, "save-a", booking("m", "s1"));
		Version theirS3 = version(OTHER, booking("m", "s3", b -> {
			b.setSeatNumber(3);
			b.setComment("Rollstuhl");
		}));
		Version theirS1 = version(OTHER, booking("t", "s1"));

		// s1 zuerst: s3 trägt m noch (die eigene Löschung dort ist offen), trotzdem entsteht _m
		Resolution s1First = POLICY.resolve(input(S1, null, myS1, theirS1, siblings(Map.entry(S3, theirS3)), theirS1));
		assertEquals(Kind.DOUBLE_SALE, s1First.note().kind());
		Change created = s1First.extra().get(0);
		assertEquals(unassigned, created.unit());
		assertTrue(created.parents().isEmpty());
		Version written = version(OWN, "resolver-1", created.payload());
		Version s1Merge = version(OWN, "resolver-1", theirS1.payload());

		// danach s3: Die Änderung von Deren geht an _m, wo m jetzt lebt
		Resolution s3Then = POLICY.resolve(input(S3, s3Base, myS3Deletion, theirS3,
				siblings(Map.entry(S1, s1Merge), Map.entry(unassigned, written)), theirS3));
		assertTrue(s3Then.deleted());
		assertEquals(1, s3Then.extra().size());
		Change transferred = s3Then.extra().get(0);
		assertEquals(unassigned, transferred.unit());
		assertFalse(transferred.deleted());
		assertEquals(List.of(written.id()), transferred.parents());
		assertEquals("Rollstuhl", transferred.payload().get("comment").asText());
		assertTrue(transferred.payload().get("unassigned").asBoolean());

		// s3 zuerst: m lebt nirgends sonst, die eigene Löschung gewinnt; danach s1: m weicht nach _m aus
		Resolution s3First = POLICY.resolve(input(S3, s3Base, myS3Deletion, theirS3, siblings(Map.entry(S1, theirS1)),
				theirS3));
		assertTrue(s3First.deleted());
		assertTrue(s3First.extra().isEmpty());
		Version s3Merge = deletion(OWN, "resolver-2");
		Resolution s1Then = POLICY.resolve(input(S1, null, myS1, theirS1, siblings(Map.entry(S3, s3Merge)), theirS1));
		assertEquals(List.of(unassigned), s1Then.extra().stream().map(Change::unit).toList());
		assertFalse(s1Then.extra().get(0).deleted());
	}

	/** Die Buchung m "ohne Platz", wie das Repository sie auf {@code _m} schreibt, danach die Änderungen aus {@code edit}. */
	private static ObjectNode withoutPlace(String id, Consumer<Booking> edit) {
		return booking(id, null, b -> {
			b.setRowLabel("");
			b.setTableNumber(0);
			b.setSeatNumber(0);
			b.setUnassigned(true);
			edit.accept(b);
		});
	}

	/** Eine Geschwister-Einheit mit ihrem Stand und dem verlorenen Ziel eines eigenen Umzugs in ihrem Ordner. */
	private static Sibling strandedTarget(UnitKey unit, Version current, Version lostMove) {
		return new Sibling(new UnitSnapshot(unit, current.id(), Status.OK, current.deleted(), current.payload()),
				ChainStep.of(current), Set.of(), lostMove);
	}

	/**
	 * R31: Meine weist m von {@code _m} dem Sitz s1 zu und ändert dabei die E-Mail (save-a: Löschung von {@code _m}, m auf
	 * s1); ein anderer Rechner ändert m auf {@code _m} (bezahlt) und verkauft t auf s1. Meine verliert auf beiden Einheiten.
	 * In beiden Reihenfolgen lebt m danach genau einmal, auf {@code _m}, mit der Änderung von Deren und der von Meine; kein
	 * DELETE_WINS, s1 trägt t mit dem Doppelverkauf an Meine.
	 */
	@Test
	void lostAssignmentFromUnassignedKeepsTheBookingOnItsUnitInEitherOrder() {
		UnitKey unassigned = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		Version base = version(THIRD, withoutPlace("m", b -> {
		}));
		Version myDeletion = deletion(OWN, "save-a");
		Version myS1 = version(OWN, "save-a", booking("m", "s1", b -> b.setEMail("neu@example.org")));
		Version theirM = version(OTHER, "save-b", withoutPlace("m", b -> b.setPaymentStatus(PaymentStatus.CARD)));
		Version theirS1 = version(OTHER, "save-b", booking("t", "s1"));
		ObjectNode expected = withoutPlace("m", b -> {
			b.setPaymentStatus(PaymentStatus.CARD);
			b.setEMail("neu@example.org");
		});

		// s1 zuerst: _m lebt (Deren), also keine Zusatz-Einheit; danach _m: Deren bleibt, die E-Mail kommt dazu
		Resolution s1First = POLICY.resolve(input(S1, null, myS1, theirS1, siblings(Map.entry(unassigned, theirM)), theirS1));
		assertEquals(theirS1.payload(), s1First.payload());
		assertEquals(Kind.DOUBLE_SALE, s1First.note().kind());
		assertEquals(unassigned, s1First.note().moved());
		assertTrue(s1First.extra().isEmpty());
		Version s1Merge = version(OWN, "resolver-1", theirS1.payload());
		Resolution mThen = POLICY.resolve(input(unassigned, base, myDeletion, theirM,
				Map.of(S1, strandedTarget(S1, s1Merge, myS1)), theirM));
		assertFalse(mThen.deleted(), "kein Storno: der Umzug ist gescheitert");
		assertEquals(expected, mThen.payload());
		assertNull(mThen.note(), "verschiedene Gruppen: still");
		assertTrue(mThen.extra().isEmpty());

		// _m zuerst: dasselbe Ergebnis; danach s1: _m lebt, also keine Zusatz-Einheit
		Resolution mFirst = POLICY.resolve(input(unassigned, base, myDeletion, theirM,
				Map.of(S1, strandedTarget(S1, theirS1, myS1)), theirM));
		assertFalse(mFirst.deleted());
		assertEquals(expected, mFirst.payload());
		assertNull(mFirst.note());
		Version mMerge = version(OWN, "resolver-2", mFirst.payload());
		Resolution s1Then = POLICY.resolve(input(S1, null, myS1, theirS1, siblings(Map.entry(unassigned, mMerge)), theirS1));
		assertEquals(theirS1.payload(), s1Then.payload());
		assertEquals(Kind.DOUBLE_SALE, s1Then.note().kind());
		assertEquals(unassigned, s1Then.note().moved());
		assertTrue(s1Then.extra().isEmpty());
	}

	@Test
	void lostAssignmentFromUnassignedNotesTheGroupBothSidesChanged() {
		UnitKey unassigned = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		Version base = version(THIRD, withoutPlace("m", b -> {
		}));
		Version myDeletion = deletion(OWN, "save-a");
		Version myS1 = version(OWN, "save-a", booking("m", "s1", b -> b.setComment("Fenster")));
		Version theirM = version(OTHER, "save-b", withoutPlace("m", b -> b.setComment("Gang")));
		Version theirS1 = version(OTHER, "save-b", booking("t", "s1"));

		Resolution resolution = POLICY.resolve(input(unassigned, base, myDeletion, theirM,
				Map.of(S1, strandedTarget(S1, theirS1, myS1)), theirM));

		assertFalse(resolution.deleted());
		assertEquals(theirM.payload(), resolution.payload(), "bei derselben Gruppe gilt Deren");
		ConflictNote note = resolution.note();
		assertEquals(Kind.FIELDS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(party(theirM), note.other());
		assertEquals(List.of("comment"), note.fields().stream().map(ConflictNote.FieldConflict::group).toList());
		assertEquals("Fenster", note.fields().get(0).mine().get("comment").asText());
		assertEquals("Gang", note.fields().get(0).theirs().get("comment").asText());
		assertNull(note.moved(), "die Änderung blieb auf der Einheit des Vermerks");
		assertEquals(myDeletion.id(), note.dropped());
	}

	/**
	 * Die Einheit {@code _m} mit ihrem Stand als Kette bis {@code current}, den Löschungen in ihrem Ordner (wie der Resolver
	 * sie liefert) und den Speichervorgängen darin.
	 */
	private static Sibling unassignedWith(UnitKey unit, Version current, List<Deletion> deletions, String... saveIds) {
		return new Sibling(new UnitSnapshot(unit, current.id(), Status.OK, current.deleted(), current.payload()),
				ChainStep.of(current), Set.of(saveIds), null, deletions);
	}

	/**
	 * R31a: Meine weist m von {@code _m} dem Sitz s1 zu (save-a); ein anderer Rechner storniert m (löscht {@code _m}) und
	 * verkauft t auf s1 (save-b). Storno gewinnt in beiden Reihenfolgen: m lebt nirgends, {@code _m} wird nicht
	 * wiederbelebt, s1 trägt t; Meine bekommt genau einen Vermerk DELETE_WINS (auf s1), keinen Doppelverkauf.
	 */
	@Test
	void cancellationWinsAgainstALostAssignmentFromUnassignedInEitherOrder() {
		UnitKey unassigned = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		Version base = version(THIRD, "save-0", withoutPlace("m", b -> {
		}));
		Version myDeletion = deletion(OWN, "save-a");
		Version myS1 = version(OWN, "save-a", booking("m", "s1", b -> b.setComment("Fenster")));
		Version theirDeletion = deletion(OTHER, "save-b");
		Version theirS1 = version(OTHER, "save-b", booking("t", "s1"));

		// s1 zuerst: _m ist fremd gelöscht, die eigene Löschung dort (save-a) ist verloren
		Resolution s1First = POLICY.resolve(input(S1, null, myS1, theirS1,
				Map.of(unassigned, unassignedWith(unassigned, theirDeletion, ownLost(), "save-0", "save-a", "save-b")),
				theirS1));
		assertCancellationWins(s1First, theirS1, myS1);
		Version s1Merge = version(OWN, "resolver-1", theirS1.payload());
		Resolution mThen = POLICY.resolve(input(unassigned, base, myDeletion, theirDeletion,
				Map.of(S1, strandedTarget(S1, s1Merge, myS1)), theirDeletion));
		assertTrue(mThen.deleted());
		assertNull(mThen.note(), "ein Vermerk für m genügt");
		assertTrue(mThen.extra().isEmpty());

		// _m zuerst: die Löschung bleibt ohne Vermerk; danach s1 wie oben
		Resolution mFirst = POLICY.resolve(input(unassigned, base, myDeletion, theirDeletion,
				Map.of(S1, strandedTarget(S1, theirS1, myS1)), theirDeletion));
		assertTrue(mFirst.deleted());
		assertNull(mFirst.note());
		assertTrue(mFirst.extra().isEmpty());
		Version mMerge = deletion(OWN, "resolver-2");
		Resolution s1Then = POLICY.resolve(input(S1, null, myS1, theirS1,
				Map.of(unassigned,
						unassignedWith(unassigned, mMerge, ownAbsorbed(), "save-0", "save-a", "save-b", "resolver-2")),
				theirS1));
		assertCancellationWins(s1Then, theirS1, myS1);
	}

	/** Die Löschungen auf {@code _m}, solange die eigene (save-a) gegen die fremde (save-b) verloren hat. */
	private static List<Deletion> ownLost() {
		return List.of(new Deletion("save-b", Standing.CHAIN), new Deletion("save-a", Standing.BESIDE));
	}

	/** Die Löschungen auf {@code _m}, nachdem die eigene Auflösung die verlorene eigene Löschung zusammengeführt hat. */
	private static List<Deletion> ownAbsorbed() {
		return List.of(new Deletion("save-b", Standing.CHAIN), new Deletion("save-a", Standing.ABSORBED));
	}

	private static void assertCancellationWins(Resolution resolution, Version theirS1, Version myS1) {
		assertFalse(resolution.deleted());
		assertEquals(theirS1.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty(), "_m wird nicht wiederbelebt");
		ConflictNote note = resolution.note();
		assertEquals(Kind.DELETE_WINS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(party(theirS1), note.other());
		assertEquals(myS1.id(), note.dropped(), "die eigene Buchung, zum Wiederherstellen");
		assertNull(note.moved());
	}

	/**
	 * R31b: Meine weist m von {@code _m} dem Sitz s1 zu (save-a); ein anderer Rechner setzt m von {@code _m} auf s4 und
	 * verkauft t auf s1 (save-b). Die fremde Verschiebung gilt in beiden Reihenfolgen: m bleibt auf s4 (nie gelöscht),
	 * {@code _m} bleibt gelöscht, s1 trägt t; Meine bekommt genau einen Vermerk FIELDS "place" (auf {@code _m}).
	 */
	@Test
	void foreignMoveWinsAgainstALostAssignmentFromUnassignedInEitherOrder() {
		UnitKey unassigned = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		UnitKey s4 = seat("s4");
		Version base = version(THIRD, "save-0", withoutPlace("m", b -> {
		}));
		Version myDeletion = deletion(OWN, "save-a");
		Version myS1 = version(OWN, "save-a", booking("m", "s1"));
		Version theirDeletion = deletion(OTHER, "save-b");
		Version theirS4 = version(OTHER, "save-b", booking("m", "s4", b -> b.setSeatNumber(4)));
		Version theirS1 = version(OTHER, "save-b", booking("t", "s1"));

		// s1 zuerst: m lebt auf s4, also keine Zusatz-Einheit und hier kein Vermerk (den bekommt _m)
		Resolution s1First = POLICY.resolve(input(S1, null, myS1, theirS1,
				Map.of(unassigned, unassignedWith(unassigned, theirDeletion, ownLost(), "save-0", "save-a", "save-b"), s4,
						sibling(s4, theirS4)),
				theirS1));
		assertForeignMoveKeptOnSeat(s1First, theirS1);
		Version s1Merge = version(OWN, "resolver-1", theirS1.payload());
		Resolution mThen = POLICY.resolve(input(unassigned, base, myDeletion, theirDeletion,
				Map.of(S1, strandedTarget(S1, s1Merge, myS1), s4, sibling(s4, theirS4)), theirDeletion));
		assertForeignMoveWinsOnUnassigned(mThen, theirDeletion, myS1);

		// _m zuerst: dasselbe
		Resolution mFirst = POLICY.resolve(input(unassigned, base, myDeletion, theirDeletion,
				Map.of(S1, strandedTarget(S1, theirS1, myS1), s4, sibling(s4, theirS4)), theirDeletion));
		assertForeignMoveWinsOnUnassigned(mFirst, theirDeletion, myS1);
		Version mMerge = deletion(OWN, "resolver-2");
		Resolution s1Then = POLICY.resolve(input(S1, null, myS1, theirS1,
				Map.of(unassigned,
						unassignedWith(unassigned, mMerge, ownAbsorbed(), "save-0", "save-a", "save-b", "resolver-2"), s4,
						sibling(s4, theirS4)),
				theirS1));
		assertForeignMoveKeptOnSeat(s1Then, theirS1);
	}

	@Test
	void foreignCopyThatMovedOnStillGivesTheLoserANote() {
		// wie R31b, aber A hat m danach von s4 nach s5 weitergesetzt (save-c): Die Kopie trägt save-b nicht mehr
		UnitKey unassigned = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		UnitKey s5 = seat("s5");
		Version base = version(THIRD, "save-0", withoutPlace("m", b -> {
		}));
		Version myDeletion = deletion(OWN, "save-a");
		Version myS1 = version(OWN, "save-a", booking("m", "s1", b -> b.setComment("Fenster")));
		Version theirDeletion = deletion(OTHER, "save-b");
		Version theirS1 = version(OTHER, "save-b", booking("t", "s1"));
		Version theirS5 = version(OTHER, "save-c", booking("m", "s5", b -> b.setSeatNumber(5)));

		Resolution resolution = POLICY.resolve(input(unassigned, base, myDeletion, theirDeletion,
				Map.of(S1, strandedTarget(S1, theirS1, myS1), s5, sibling(s5, theirS5)), theirDeletion));

		assertTrue(resolution.deleted());
		assertTrue(resolution.extra().isEmpty(), "s5 wird nicht gelöscht");
		ConflictNote note = resolution.note();
		assertEquals(Kind.FIELDS, note.kind(), "B's gescheitertes Zuweisen bleibt nicht still");
		assertEquals("s5", note.fields().get(0).theirs().get("seatId").asText());
		assertEquals(myS1.id(), note.dropped());
	}

	private static void assertForeignMoveKeptOnSeat(Resolution resolution, Version theirS1) {
		assertFalse(resolution.deleted());
		assertEquals(theirS1.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty(), "_m wird nicht wiederbelebt");
		assertNull(resolution.note(), "der Vermerk steht auf _m");
	}

	private static void assertForeignMoveWinsOnUnassigned(Resolution resolution, Version theirDeletion, Version myS1) {
		assertTrue(resolution.deleted());
		assertTrue(resolution.extra().isEmpty(), "s4 wird nie gelöscht");
		ConflictNote note = resolution.note();
		assertEquals(Kind.FIELDS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(party(theirDeletion), note.other());
		assertEquals(List.of("place"), note.fields().stream().map(ConflictNote.FieldConflict::group).toList());
		assertEquals("s1", note.fields().get(0).mine().get("seatId").asText());
		assertEquals("s4", note.fields().get(0).theirs().get("seatId").asText());
		assertEquals(myS1.id(), note.dropped());
	}

	@Test
	void ownEarlierAssignmentFromUnassignedStillRevivesOnADoubleSale() {
		// R21: _m wurde früher in einem anderen Speichervorgang gelöscht (zugewiesen); kein Storno, _m baut auf der Löschung auf
		UnitKey unassigned = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		Version earlierDeletion = deletion(OWN, "save-early");
		Version myS1 = version(OWN, "save-a", booking("m", "s1"));
		Version theirS1 = version(OTHER, "save-b", booking("t", "s1"));

		Resolution resolution = POLICY.resolve(input(S1, null, myS1, theirS1,
				Map.of(unassigned, unassignedWith(unassigned, earlierDeletion,
						List.of(new Deletion("save-early", Standing.CHAIN)), "save-0", "save-early")),
				theirS1));

		assertEquals(Kind.DOUBLE_SALE, resolution.note().kind());
		assertEquals(List.of(unassigned), resolution.extra().stream().map(Change::unit).toList());
		assertEquals(List.of(earlierDeletion.id()), resolution.extra().get(0).parents());
	}

	@Test
	void cancellationOfUnassignedBookingStillWinsWithoutStrandedMove() {
		// ohne verlorenes Umzugsziel bleibt es eine Stornierung von _m: Löschung gewinnt
		UnitKey unassigned = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		Version base = version(THIRD, withoutPlace("m", b -> {
		}));
		Version myDeletion = deletion(OWN, "save-a");
		Version theirM = version(OTHER, "save-b", withoutPlace("m", b -> b.setPaymentStatus(PaymentStatus.CARD)));
		Version theirS1 = version(OTHER, "save-b", booking("t", "s1"));

		Resolution resolution = POLICY.resolve(input(unassigned, base, myDeletion, theirM,
				siblings(Map.entry(S1, theirS1)), theirM));

		assertTrue(resolution.deleted());
		assertEquals(Kind.DELETE_WINS, resolution.note().kind());
	}

	// R35: Zuweisen aus "ohne Platz" gegen eine gleichzeitige Änderung, als Matrix -->

	/** Was A mit m macht, während es s1 an t verkauft. */
	enum ForeignAction {
		/** m auf {@code _m} ändern (bezahlt). */
		EDIT,
		/** m stornieren ({@code _m} löschen). */
		CANCEL,
		/** m von {@code _m} nach s4 setzen. */
		MOVE_TO_S4,
		/** m nach s4 setzen und in einem zweiten Speichervorgang weiter nach s5. */
		MOVE_TO_S4_THEN_S5
	}

	/** Wer welche Einheit gewinnt, und wer in welcher Reihenfolge auflöst. */
	enum Course {
		/** A gewinnt beide Einheiten; B löst erst s1, dann {@code _m} auf. */
		A_WINS_BOTH_SEAT_FIRST(false),
		/** A gewinnt beide Einheiten; B löst erst {@code _m}, dann s1 auf. */
		A_WINS_BOTH_UNASSIGNED_FIRST(false),
		/** B gewinnt {@code _m}, A gewinnt s1; B löst s1 auf, bevor A {@code _m} auflöst (und A sieht B's Ergebnis). */
		SPLIT_SEAT_BEFORE_FOREIGN_MERGE(true),
		/** B gewinnt {@code _m}, A gewinnt s1; A löst {@code _m} auf, danach B s1 (und B sieht A's Zusammenführung). */
		SPLIT_SEAT_AFTER_FOREIGN_MERGE(true);

		final boolean split;

		Course(boolean split) {
			this.split = split;
		}
	}

	/**
	 * Ein Versionsspeicher im Kleinen: je Einheit alle Versionen ihres Ordners und die gültige Kette, je Einheit und
	 * Rechner die verlorene Strecke. {@link #resolve} bildet die Eingabe wie {@code ConflictResolver} (Basis, Meine, Deren,
	 * Kette seit der Basis, Speichervorgänge der Strecke, Geschwister mit Speichervorgängen, verlorenem Umzugsziel und
	 * Löschungen) und schreibt die Auflösung (weitere Änderungen, dann die Zusammenführung) in die Ketten. Gabelungen
	 * zwischen Auflösungen gibt es hier nicht: Wer auflöst, sieht alles, was vorher aufgelöst wurde.
	 */
	private static final class World {
		private final Map<UnitKey, List<Version>> chains = new LinkedHashMap<>();
		private final Map<UnitKey, List<Version>> folders = new LinkedHashMap<>();
		private final Map<UnitKey, Map<String, List<Version>>> lost = new LinkedHashMap<>();
		/** Die geschriebenen Vermerke. */
		final List<ConflictNote> notes = new ArrayList<>();
		private int merges;

		void claim(UnitKey unit, Version version) {
			List<Version> chain = chains.computeIfAbsent(unit, key -> new ArrayList<>());
			assertEquals(chain.isEmpty() ? null : chain.get(chain.size() - 1).id(), version.firstParent(),
					"keine Gabelung an " + unit.relativePath());
			chain.add(version);
			folders.computeIfAbsent(unit, key -> new ArrayList<>()).add(version);
		}

		void lose(UnitKey unit, Version version) {
			folders.computeIfAbsent(unit, key -> new ArrayList<>()).add(version);
			lost.computeIfAbsent(unit, key -> new LinkedHashMap<>())
					.computeIfAbsent(version.machine(), key -> new ArrayList<>()).add(version);
		}

		Version head(UnitKey unit) {
			List<Version> chain = chains.getOrDefault(unit, List.of());
			return chain.isEmpty() ? null : chain.get(chain.size() - 1);
		}

		/** Die Einheiten, deren Stand die Buchung trägt. */
		List<UnitKey> carriers(String bookingId) {
			return chains.keySet().stream().filter(unit -> bookingId.equals(idOf(head(unit)))).toList();
		}

		/** Ob kein Rechner mehr eine verlorene Strecke hat. */
		boolean settled() {
			return lost.values().stream().allMatch(Map::isEmpty);
		}

		/** Die Vermerke, die einen Rechner betreffen. */
		List<ConflictNote> notesFor(String machine) {
			return notes.stream().filter(note -> machine.equals(note.affected())).toList();
		}

		/** Löst die verlorene Strecke des Rechners an der Einheit auf, wie der Resolver auf diesem Rechner. */
		void resolve(UnitKey unit, String machine) {
			List<Version> stretch = lost.get(unit).remove(machine);
			Version mine = stretch.get(stretch.size() - 1);
			VersionId baseId = stretch.get(0).firstParent();
			List<Version> chain = chains.get(unit);
			int at = -1;
			for (int i = 0; baseId != null && i < chain.size(); i++) {
				if (chain.get(i).id().equals(baseId)) {
					at = i;
				}
			}
			Version base = at < 0 ? null : chain.get(at);
			Version theirs = head(unit);
			Set<String> saveIds = stretch.stream().map(Version::saveId).collect(Collectors.toSet());
			ConflictInput input = new ConflictInput(unit, copy(base), copy(mine), copy(theirs),
					chain.subList(at + 1, chain.size()).stream().map(BookingPolicyTest::copy).toList(), machine,
					siblings(unit, base, mine, saveIds), saveIds);
			Resolution resolution = POLICY.resolve(input);
			String saveId = "merge-" + ++merges;
			for (Change extra : resolution.extra()) {
				Version current = head(extra.unit());
				boolean there = extra.deleted() ? current == null || current.deleted()
						: extra.parents().isEmpty() && current != null;
				if (!there) {
					write(extra.unit(), extra.parents(), machine, saveId, extra.deleted(), extra.payload(), extra.note());
				}
			}
			write(unit, List.of(theirs.id(), mine.id()), machine, saveId, resolution.deleted(), resolution.payload(),
					resolution.note());
		}

		private void write(UnitKey unit, List<VersionId> parents, String machine, String saveId, boolean deleted,
				ObjectNode payload, ConflictNote note) {
			claim(unit, new Version(1, VersionId.create(1_800_000_000_000L + counter, machine, counter++), parents, machine,
					"Kasse " + machine, "Bediener " + machine, Instant.EPOCH, saveId, deleted, deleted ? null : payload,
					note, Map.of()));
			if (note != null) {
				notes.add(note);
			}
		}

		/** Die Geschwister wie {@code ConflictResolver.siblings}. */
		private Map<UnitKey, Sibling> siblings(UnitKey unit, Version base, Version mine, Set<String> saveIds) {
			String bookingId = idOf(base);
			String mineId = idOf(mine);
			UnitKey ownUnassigned = mineId == null ? null : UnitKey.unassignedBooking(EVENT, PRESENTATION, mineId);
			Map<UnitKey, Sibling> siblings = new LinkedHashMap<>();
			for (UnitKey other : chains.keySet()) {
				Version head = head(other);
				if (other.equals(unit)) {
					continue;
				}
				boolean carries = bookingId != null && bookingId.equals(idOf(head));
				boolean carriesMine = mineId != null && mineId.equals(idOf(head));
				Set<String> folderSaves = carries || carriesMine || other.equals(ownUnassigned)
						? folders.get(other).stream().map(Version::saveId).collect(Collectors.toSet())
						: Set.of();
				Version lostMove = carries || bookingId == null || !mine.deleted() ? null
						: youngest(other, version -> !chains.get(other).contains(version)
								&& saveIds.contains(version.saveId()) && bookingId.equals(idOf(version)));
				List<Deletion> deletions = other.equals(ownUnassigned) ? deletions(other, saveIds) : List.of();
				siblings.put(other, new Sibling(new UnitSnapshot(other, head.id(), Status.OK, head.deleted(),
						head.deleted() ? null : head.payload().deepCopy()), ChainStep.of(head), folderSaves, copy(lostMove),
						deletions));
			}
			return siblings;
		}

		private Version youngest(UnitKey unit, Predicate<Version> filter) {
			return folders.get(unit).stream().filter(filter).reduce((first, second) -> second).orElse(null);
		}

		private List<Deletion> deletions(UnitKey unit, Set<String> saveIds) {
			Set<VersionId> earlier = new HashSet<>();
			folders.get(unit).stream().filter(version -> saveIds.contains(version.saveId()))
					.forEach(version -> earlier.addAll(ancestors(unit, version)));
			Set<VersionId> absorbed = ancestors(unit, head(unit));
			List<Deletion> deletions = new ArrayList<>();
			for (Version version : folders.get(unit)) {
				if (version.deleted() && version.parents().size() < 2 && !earlier.contains(version.id())) {
					Standing standing = chains.get(unit).contains(version) ? Standing.CHAIN
							: absorbed.contains(version.id()) ? Standing.ABSORBED : Standing.BESIDE;
					deletions.add(new Deletion(version.saveId(), standing));
				}
			}
			return deletions;
		}

		/** Die Vorgänger der Version im Ordner, über alle Vorgänger, ohne sie selbst. */
		private Set<VersionId> ancestors(UnitKey unit, Version version) {
			Set<VersionId> found = new HashSet<>();
			List<VersionId> open = new ArrayList<>(version.parents());
			while (!open.isEmpty()) {
				VersionId id = open.remove(open.size() - 1);
				if (found.add(id)) {
					folders.get(unit).stream().filter(candidate -> candidate.id().equals(id)).findFirst()
							.ifPresent(parent -> open.addAll(parent.parents()));
				}
			}
			return found;
		}
	}

	/** Eine Kopie der Version, wie der Resolver sie der Strategie gibt. */
	private static Version copy(Version version) {
		return version == null ? null
				: new Version(version.format(), version.id(), version.parents(), version.machine(), version.machineName(),
						version.operator(), version.at(), version.saveId(), version.deleted(),
						version.payload() == null ? null : version.payload().deepCopy(), version.conflict(), version.seen());
	}

	/** Die Buchungs-ID einer Version mit Inhalt; {@code null} ohne Version oder bei einer Löschung. */
	private static String idOf(Version version) {
		return version == null || version.deleted() ? null : version.payload().path("id").asText(null);
	}

	/** Eine Version auf einem Vorgänger; ohne Inhalt eine Löschung. */
	private static Version child(String machine, String saveId, Version parent, ObjectNode payload) {
		return new Version(1, VersionId.create(1_800_000_000_000L + counter, machine, counter++), List.of(parent.id()),
				machine, "Kasse " + machine, "Bediener " + machine, Instant.EPOCH, saveId, payload == null, payload);
	}

	/**
	 * Der Fall aus R35: m steht "ohne Platz" ({@code _m}). B (dieser Rechner, OWN) weist m dem Sitz s1 zu und ändert dabei die
	 * E-Mail (save-a: Löschung von {@code _m}, m auf s1), bei zwei Speichervorgängen danach noch den Kommentar an m auf s1
	 * (save-c). A (OTHER) verkauft s1 an t und ändert, storniert oder verschiebt m im selben Speichervorgang (save-b);
	 * weitergeschoben nach s5 wird in save-d. A gewinnt s1, {@code _m} gewinnt je nach Verlauf A oder B; danach lösen die
	 * Verlierer in der Reihenfolge des Verlaufs auf.
	 */
	private static World assignmentAgainst(ForeignAction action, int ownSaves, Course course) {
		World world = new World();
		UnitKey unassigned = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		UnitKey s4 = seat("s4");
		Version base = version(THIRD, "save-0", withoutPlace("m", b -> {
		}));
		world.claim(unassigned, base);
		Version myDeletion = child(OWN, "save-a", base, null);
		List<Version> mySeat = new ArrayList<>(
				List.of(version(OWN, "save-a", booking("m", "s1", b -> b.setEMail("neu@example.org")))));
		if (ownSaves == 2) {
			mySeat.add(child(OWN, "save-c", mySeat.get(0), booking("m", "s1", b -> {
				b.setEMail("neu@example.org");
				b.setComment("Fenster");
			})));
		}
		Version theirSeat = version(OTHER, "save-b", booking("t", "s1", b -> b.setLastName("Tom")));
		Version theirM = action == ForeignAction.EDIT
				? child(OTHER, "save-b", base, withoutPlace("m", b -> b.setPaymentStatus(PaymentStatus.CARD)))
				: child(OTHER, "save-b", base, null);
		world.claim(S1, theirSeat);
		mySeat.forEach(version -> world.lose(S1, version));
		if (action == ForeignAction.MOVE_TO_S4 || action == ForeignAction.MOVE_TO_S4_THEN_S5) {
			Version theirS4 = version(OTHER, "save-b", booking("m", "s4", b -> b.setSeatNumber(4)));
			world.claim(s4, theirS4);
			if (action == ForeignAction.MOVE_TO_S4_THEN_S5) {
				world.claim(s4, child(OTHER, "save-d", theirS4, null));
				world.claim(seat("s5"), version(OTHER, "save-d", booking("m", "s5", b -> b.setSeatNumber(5))));
			}
		}
		if (course.split) {
			world.claim(unassigned, myDeletion);
			world.lose(unassigned, theirM);
		} else {
			world.claim(unassigned, theirM);
			world.lose(unassigned, myDeletion);
		}
		switch (course) {
		case A_WINS_BOTH_SEAT_FIRST -> {
			world.resolve(S1, OWN);
			world.resolve(unassigned, OWN);
		}
		case A_WINS_BOTH_UNASSIGNED_FIRST -> {
			world.resolve(unassigned, OWN);
			world.resolve(S1, OWN);
		}
		case SPLIT_SEAT_BEFORE_FOREIGN_MERGE -> {
			world.resolve(S1, OWN);
			world.resolve(unassigned, OTHER);
		}
		case SPLIT_SEAT_AFTER_FOREIGN_MERGE -> {
			world.resolve(unassigned, OTHER);
			world.resolve(S1, OWN);
		}
		}
		assertTrue(world.settled());
		return world;
	}

	/**
	 * Die Zellen, die die Matrix nicht halten kann: A verschiebt m nach s4, die Gewinner sind getrennt, und A löst
	 * {@code _m} auf, bevor B's Auflösung von s1 bei ihm ist.
	 */
	private static boolean heldOpen(ForeignAction action, Course course) {
		return action == ForeignAction.MOVE_TO_S4 && course == Course.SPLIT_SEAT_AFTER_FOREIGN_MERGE;
	}

	static Stream<Arguments> assignmentCells() {
		return cells(cell -> !heldOpen(cell.action(), cell.course()));
	}

	static Stream<Arguments> heldOpenCells() {
		return cells(cell -> heldOpen(cell.action(), cell.course()));
	}

	private record Cell(ForeignAction action, int ownSaves, Course course) {
	}

	private static Stream<Arguments> cells(Predicate<Cell> filter) {
		List<Arguments> cells = new ArrayList<>();
		for (ForeignAction action : ForeignAction.values()) {
			for (int ownSaves = 1; ownSaves <= 2; ownSaves++) {
				for (Course course : Course.values()) {
					if (filter.test(new Cell(action, ownSaves, course))) {
						cells.add(Arguments.of(action, ownSaves, course));
					}
				}
			}
		}
		return cells.stream();
	}

	/**
	 * R35: In jeder Variante und jeder Reihenfolge lebt m höchstens einmal, und nirgends genau dann, wenn A storniert hat;
	 * A's Umzug gilt, eine Änderung von A weicht nie still; B weicht mit genau einem Vermerk (Änderung: Doppelverkauf,
	 * Storno: DELETE_WINS, Umzug: FIELDS "Platz"), A hat nur dann einen, wenn seine Änderung weicht; s1 trägt t.
	 */
	@ParameterizedTest(name = "{0}, B speichert {1}x, {2}")
	@MethodSource("assignmentCells")
	void assignmentFromUnassignedAgainstConcurrentChangeKeepsInvariants(ForeignAction action, int ownSaves,
			Course course) {
		assertInvariants(assignmentAgainst(action, ownSaves, course), action);
	}

	/** Dieselben Invarianten für die Zellen, die ohne einen neuen Mechanismus im Speicher nicht zu halten sind. */
	@Disabled("Ruling offen (Bericht Runde 4): Löst A _m auf, bevor B's Auflösung von s1 bei ihm ist, hält A B's "
			+ "gewonnene Löschung für ein Storno und löscht seine Kopie auf s4 (Regel 0, verschoben gegen storniert), weil "
			+ "die Strategie B's verlorenes Ziel auf s1 nicht sieht; dazu müsste der Speicher fremde verlorene Versionen "
			+ "anderer Einheiten liefern, die der Index heute nicht kennt")
	@ParameterizedTest(name = "{0}, B speichert {1}x, {2}")
	@MethodSource("heldOpenCells")
	void foreignMoveAgainstAssignmentWithSplitWinnersKeepsInvariants(ForeignAction action, int ownSaves,
			Course course) {
		assertInvariants(assignmentAgainst(action, ownSaves, course), action);
	}

	/**
	 * Was in den offenen Zellen gilt: nie zwei lebende Einheiten, B hat genau einen Vermerk, und lebt m nirgends, hat A
	 * einen Vermerk DELETE_WINS auf seine Kopie (zum Wiederherstellen). Nichts geht still verloren.
	 */
	@ParameterizedTest(name = "{0}, B speichert {1}x, {2}")
	@MethodSource("heldOpenCells")
	void foreignMoveAgainstAssignmentWithSplitWinnersLosesNothingSilently(ForeignAction action, int ownSaves,
			Course course) {
		World world = assignmentAgainst(action, ownSaves, course);
		List<UnitKey> carriers = world.carriers("m");
		assertTrue(carriers.size() <= 1, "m lebt höchstens einmal: " + carriers);
		assertEquals(1, world.notesFor(OWN).size(), "B: " + world.notesFor(OWN));
		if (carriers.isEmpty()) {
			List<ConflictNote> theirs = world.notesFor(OTHER);
			assertEquals(1, theirs.size(), "A: " + theirs);
			assertEquals(Kind.DELETE_WINS, theirs.get(0).kind());
		}
		assertEquals("t", idOf(world.head(S1)));
	}

	private static void assertInvariants(World world, ForeignAction action) {
		List<UnitKey> carriers = world.carriers("m");
		assertTrue(carriers.size() <= 1, "1: m lebt höchstens einmal: " + carriers);
		assertEquals(action == ForeignAction.CANCEL ? 0 : 1, carriers.size(),
				"2: m lebt genau dann nirgends, wenn A storniert hat: " + carriers);
		boolean theirChangeYields = false;
		switch (action) {
		case MOVE_TO_S4 -> assertEquals(List.of(seat("s4")), carriers, "3: A's Umzug gilt");
		case MOVE_TO_S4_THEN_S5 -> assertEquals(List.of(seat("s5")), carriers, "3: A's Umzug gilt");
		case EDIT -> theirChangeYields = !"CARD".equals(world.head(carriers.get(0)).payload().path("paymentStatus").asText());
		case CANCEL -> {
		}
		}
		List<ConflictNote> mine = world.notesFor(OWN);
		assertEquals(1, mine.size(), "4: B weicht mit genau einem Vermerk: " + mine);
		Kind expected = switch (action) {
		case EDIT -> Kind.DOUBLE_SALE;
		case CANCEL -> Kind.DELETE_WINS;
		case MOVE_TO_S4, MOVE_TO_S4_THEN_S5 -> Kind.FIELDS;
		};
		assertEquals(expected, mine.get(0).kind(), "4: Art von B's Vermerk");
		if (expected == Kind.FIELDS) {
			assertEquals(List.of("place"), mine.get(0).fields().stream().map(ConflictNote.FieldConflict::group).toList());
		}
		List<ConflictNote> theirs = world.notesFor(OTHER);
		assertEquals(theirChangeYields ? 1 : 0, theirs.size(),
				"3/4: A hat genau dann einen Vermerk, wenn seine Änderung weicht: " + theirs);
		if (theirChangeYields) {
			assertEquals(Kind.DELETE_WINS, theirs.get(0).kind());
		}
		assertEquals("t", idOf(world.head(S1)), "5: s1 trägt t");
	}

	@Test
	void bothReplacedWithDifferentBookingsIsADoubleSale() {
		// keine Seite trägt mehr x: zwei neue Verkäufe auf demselben Sitz
		Version base = version(THIRD, booking("x", "s1"));
		Version mine = version(OWN, booking("m", "s1"));
		Version theirs = version(OTHER, booking("t", "s1"));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertEquals(Kind.DOUBLE_SALE, resolution.note().kind());
		assertEquals(UnitKey.unassignedBooking(EVENT, PRESENTATION, "m"), resolution.extra().get(0).unit());
	}

	@Test
	void doubleSaleWithAnAliveUnassignedCopyCreatesNoSecondUnit() {
		// die Buchung m lebt schon ohne Platz: keine zweite lebende Einheit mit derselben Buchungs-ID
		UnitKey moved = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		Version alive = version(OTHER, booking("m", null, b -> {
			b.setRowLabel("");
			b.setTableNumber(0);
			b.setSeatNumber(0);
			b.setUnassigned(true);
		}));
		Version mine = version(OWN, booking("m", "s1"));
		Version theirs = version(OTHER, booking("t", "s1"));

		Resolution resolution = POLICY.resolve(input(S1, null, mine, theirs, siblings(Map.entry(moved, alive)), theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty());
		assertEquals(Kind.DOUBLE_SALE, resolution.note().kind());
		assertEquals(moved, resolution.note().moved());
		assertEquals(mine.id(), resolution.note().dropped());
	}

	@Test
	void unchangedMineTakesTheirsWithoutMovingIt() {
		Version base = version(THIRD, booking("x", "s1"));
		Version mine = version(OWN, booking("x", "s1"));
		Version theirs = version(OTHER, booking("y", "s1"));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertNull(resolution.note());
		assertTrue(resolution.extra().isEmpty());
	}

	@Test
	void unreadableTheirsIsTreatedAsDoubleSale() {
		Version mine = version(OWN, booking("m", "s1"));
		// Deren lässt sich nicht lesen: Der Zahlstatus ist keiner; die ID ist sogar dieselbe
		ObjectNode broken = booking("m", "s1");
		broken.put("paymentStatus", "KAPUTT");
		Version theirs = version(OTHER, broken);

		Resolution resolution = POLICY.resolve(input(S1, null, mine, theirs, theirs));

		assertFalse(resolution.deleted());
		assertSame(theirs.payload(), resolution.payload());
		assertEquals(UnitKey.unassignedBooking(EVENT, PRESENTATION, "m"), resolution.extra().get(0).unit());
		assertEquals(Kind.DOUBLE_SALE, resolution.note().kind());
		assertEquals(OWN, resolution.note().affected());
		assertEquals(party(theirs), resolution.note().other());

		// gesperrt (kein Inhalt): ebenso
		Version locked = version(OTHER, null);
		Resolution lockedResolution = POLICY.resolve(input(S1, null, mine, locked, locked));
		assertEquals(Kind.DOUBLE_SALE, lockedResolution.note().kind());
		assertEquals(1, lockedResolution.extra().size());
		assertNull(lockedResolution.payload());
	}

	@Test
	void unreadableTheirsOnAnUnassignedUnitIsUnmerged() {
		// Eine Buchung ohne Platz kann nicht weiter ausweichen: Deren bleibt, der Vermerk sagt es diesem Rechner
		UnitKey unit = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		Version mine = version(OWN, booking("m", null, b -> b.setUnassigned(true)));
		ObjectNode broken = booking("m", null, b -> b.setUnassigned(true));
		broken.put("tableNumber", "keine Zahl");
		Version theirs = version(OTHER, broken);

		Resolution resolution = POLICY.resolve(input(unit, null, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty());
		assertEquals(Kind.UNMERGED, resolution.note().kind());
		assertEquals(OWN, resolution.note().affected());
		assertEquals(mine.id(), resolution.note().dropped());
	}

	@Test
	void unreadableMineIsUnmerged() {
		Version base = version(THIRD, booking("x", "s1"));
		ObjectNode broken = booking("x", "s1");
		broken.put("seatNumber", "keine Zahl");
		Version mine = version(OWN, broken);
		Version theirs = version(OTHER, booking("x", "s1", b -> b.setComment("neu")));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty());
		assertEquals(Kind.UNMERGED, resolution.note().kind());
		assertEquals(OWN, resolution.note().affected());
	}

	@Test
	void doubleSaleSurvivesBookingSyncInAnyOrder() throws IOException {
		Hall hall = new Hall();
		SeatingPlan plan = hall.addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, 1, 2);
		Table table = plan.getTables().get(0);
		Seat seat = table.getSeats().get(0);
		UnitKey unit = seat(seat.getId());
		Version mine = version(OWN, booking("m", seat.getId(), b -> b.takeSnapshot(table, seat)));
		Version theirs = version(OTHER, booking("t", seat.getId(), b -> {
			b.takeSnapshot(table, seat);
			b.setLastName("Schulz");
		}));

		Resolution resolution = POLICY.resolve(input(unit, null, mine, theirs, theirs));

		for (boolean winnerFirst : new boolean[] { true, false }) {
			Booking winner = MAPPER.treeToValue(resolution.payload(), Booking.class);
			Booking loser = MAPPER.treeToValue(resolution.extra().get(0).payload(), Booking.class);
			Presentation presentation = new Presentation();
			presentation.setSeatingPlanId(plan.getId());
			presentation.setBookings(new ArrayList<>(winnerFirst ? List.of(winner, loser) : List.of(loser, winner)));

			BookingSync.sync(presentation, plan);

			assertEquals(seat.getId(), winner.getSeatId(), "Reihenfolge Gewinner zuerst: " + winnerFirst);
			assertFalse(winner.isUnassigned());
			assertTrue(loser.isUnassigned());
			assertNull(loser.getSeatId());

			// der Gewinner wird storniert: Der Verlierer rückt nicht still auf den frei gewordenen Sitz
			winner.clearCustomerData();
			BookingSync.sync(presentation, plan);

			assertTrue(loser.isUnassigned(), "Reihenfolge Gewinner zuerst: " + winnerFirst);
			assertNull(loser.getSeatId());
			assertEquals("Muster", loser.getLastName());
			assertTrue(presentation.getBookings().contains(loser));
		}
	}

	// <-- Regel 3

	// Regel 2: Löschen gewinnt -->

	@Test
	void deletionAgainstEditBothDirections() {
		Version base = version(THIRD, booking("x", "s1"));

		// Deren hat storniert, Meine geändert
		Version mine = version(OWN, booking("x", "s1", b -> b.setComment("Abholung Freitag")));
		Version theirDeletion = deletion(OTHER);
		Resolution lost = POLICY.resolve(input(S1, base, mine, theirDeletion, theirDeletion));

		assertTrue(lost.deleted());
		assertTrue(lost.extra().isEmpty());
		assertEquals(Kind.DELETE_WINS, lost.note().kind());
		assertEquals(OWN, lost.note().affected());
		assertEquals(party(theirDeletion), lost.note().other());
		assertEquals(mine.id(), lost.note().dropped());

		// Meine hat storniert, Deren dieselbe Buchung geändert
		Version myDeletion = deletion(OWN);
		Version theirEdit = version(OTHER, booking("x", "s1", b -> b.setPaymentStatus(PaymentStatus.CASH)));
		Resolution won = POLICY.resolve(input(S1, base, myDeletion, theirEdit, theirEdit));

		assertTrue(won.deleted());
		assertTrue(won.extra().isEmpty());
		assertEquals(Kind.DELETE_WINS, won.note().kind());
		assertEquals(OTHER, won.note().affected());
		assertEquals(party(myDeletion), won.note().other());
		assertEquals(theirEdit.id(), won.note().dropped());
	}

	@Test
	void newSaleOnASeatTheirsCancelledWins() {
		// Deren storniert x; Meine verkauft auf dem Sitz eine andere Buchung y: Die Stornierung trifft y nicht
		Version base = version(THIRD, booking("x", "s1"));
		Version theirDeletion = deletion(OTHER);
		Version mine = version(OWN, booking("y", "s1", b -> b.setLastName("Neu")));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirDeletion, theirDeletion));

		assertFalse(resolution.deleted());
		assertEquals(mine.payload(), resolution.payload());
		assertNull(resolution.note());
		assertTrue(resolution.extra().isEmpty());
	}

	@Test
	void newSaleWithoutBaseAgainstACancelledSaleWins() {
		// zwei Erstverkäufe auf einem freien Sitz; Deren hat seinen Verkauf t inzwischen storniert
		Version sold = version(OTHER, booking("t", "s1"));
		Version theirDeletion = deletion(OTHER);
		Version mine = version(OWN, booking("m", "s1"));

		Resolution resolution = POLICY.resolve(input(S1, null, mine, theirDeletion, sold, theirDeletion));

		assertFalse(resolution.deleted());
		assertEquals(mine.payload(), resolution.payload());
		assertNull(resolution.note());
		assertTrue(resolution.extra().isEmpty());
	}

	@Test
	void unreadableNewSaleOnACancelledSeatIsUnmerged() {
		Version base = version(THIRD, booking("x", "s1"));
		Version theirDeletion = deletion(OTHER);
		ObjectNode broken = booking("y", "s1");
		broken.put("seatNumber", "keine Zahl");
		Version mine = version(OWN, broken);

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirDeletion, theirDeletion));

		assertTrue(resolution.deleted());
		assertTrue(resolution.extra().isEmpty());
		assertEquals(Kind.UNMERGED, resolution.note().kind());
		assertEquals(OWN, resolution.note().affected());
		assertEquals(mine.id(), resolution.note().dropped());
	}

	@Test
	void theirMoveAgainstMyEditTransfersTheEdit() {
		// Deren verschiebt x von s1 nach s2 (Löschung auf s1), Meine ändert x auf s1: Die Änderung geht nach s2
		Version base = version(THIRD, booking("x", "s1"));
		Version theirDeletion = deletion(OTHER, "save-move");
		Version theirCopy = version(OTHER, "save-move", booking("x", "s2", b -> b.setSeatNumber(2)));
		Version mine = version(OWN, booking("x", "s1", b -> b.setComment("Abholung Freitag")));

		Resolution resolution = POLICY.resolve(
				input(S1, base, mine, theirDeletion, siblings(Map.entry(S2, theirCopy)), theirDeletion));

		assertTrue(resolution.deleted());
		assertNull(resolution.note());
		ObjectNode merged = booking("x", "s2", b -> {
			b.setSeatNumber(2);
			b.setComment("Abholung Freitag");
		});
		assertEquals(List.of(new Change(S2, List.of(theirCopy.id()), merged, false)), resolution.extra());
	}

	@Test
	void myMoveAgainstTheirEditTransfersTheirEditAndReportsConflicts() {
		// Meine verschiebt x nach s2 und ändert dort später den Kommentar; Deren ändert den Kommentar auf s1 anders
		Version base = version(THIRD, booking("x", "s1"));
		Version myDeletion = deletion(OWN, "save-move");
		Version myCopy = version(OWN, "save-later", booking("x", "s2", b -> {
			b.setSeatNumber(2);
			b.setComment("meiner");
		}));
		Version theirs = version(OTHER, booking("x", "s1", b -> {
			b.setComment("deren");
			b.setPaymentStatus(PaymentStatus.TRANSFER);
		}));
		Sibling s2 = new Sibling(new UnitSnapshot(S2, myCopy.id(), Status.OK, false, myCopy.payload()),
				ChainStep.of(myCopy), Set.of("save-move", "save-later"));

		Resolution resolution = POLICY.resolve(input(S1, base, myDeletion, theirs, Map.of(S2, s2), theirs));

		assertTrue(resolution.deleted());
		ObjectNode merged = booking("x", "s2", b -> {
			b.setSeatNumber(2);
			b.setComment("meiner");
			b.setPaymentStatus(PaymentStatus.TRANSFER);
		});
		assertEquals(List.of(new Change(S2, List.of(myCopy.id()), merged, false)), resolution.extra());
		ConflictNote note = resolution.note();
		assertEquals(Kind.FIELDS, note.kind());
		assertEquals(OTHER, note.affected());
		assertEquals(party(myDeletion), note.other());
		assertEquals(theirs.id(), note.dropped());
		assertEquals(S2, note.moved());
		assertEquals("comment", note.fields().get(0).group());
		assertEquals("deren", note.fields().get(0).mine().get("comment").asText());
		assertEquals("meiner", note.fields().get(0).theirs().get("comment").asText());
	}

	@Test
	void cancellationAgainstAnotherBookingKeepsIt() {
		// Meine storniert x; auf dem Sitz steht inzwischen die Buchung y: Die Stornierung trifft y nicht
		Version base = version(THIRD, booking("x", "s1"));
		Version cancelled = deletion(OTHER);
		Version resold = version(OTHER, booking("y", "s1"));
		Version myDeletion = deletion(OWN);

		Resolution resolution = POLICY.resolve(input(S1, base, myDeletion, resold, cancelled, resold));

		assertFalse(resolution.deleted());
		assertEquals(resold.payload(), resolution.payload());
		assertNull(resolution.note());
		assertTrue(resolution.extra().isEmpty());
	}

	// <-- Regel 2

	// Regel 0: beide gelöscht -->

	@Test
	void moveAgainstCancelDeletesTheMovedCopy() {
		// Rechner B (dieser) verschiebt x von s1 nach s2; Rechner A storniert x auf s1
		Version base = version(THIRD, booking("x", "s1"));
		Version myDeletion = deletion(OWN, "save-move");
		Version myCopy = version(OWN, "save-move", booking("x", "s2", b -> b.setSeatNumber(2)));
		Version unrelated = version(THIRD, booking("z", "s3"));
		Version theirDeletion = deletion(OTHER, "save-cancel");
		Map<UnitKey, Sibling> siblings = siblings(Map.entry(S2, myCopy), Map.entry(S3, unrelated));

		Resolution resolution = POLICY.resolve(input(S1, base, myDeletion, theirDeletion, siblings, theirDeletion));

		assertTrue(resolution.deleted());
		assertEquals(List.of(deletionOf(S2, myCopy)), resolution.extra());
		ConflictNote note = resolution.note();
		assertEquals(Kind.DELETE_WINS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(party(theirDeletion), note.other());
		assertEquals(myCopy.id(), note.dropped());
	}

	@Test
	void cancelAgainstMoveDeletesTheirMovedCopy() {
		// dieser Rechner storniert x auf s1; Rechner A verschiebt x nach s2 und gewinnt den Anspruch
		Version base = version(THIRD, booking("x", "s1"));
		Version myDeletion = deletion(OWN, "save-cancel");
		Version theirDeletion = deletion(OTHER, "save-move");
		Version theirCopy = version(OTHER, "save-move", booking("x", "s2", b -> b.setSeatNumber(2)));
		Map<UnitKey, Sibling> siblings = siblings(Map.entry(S2, theirCopy));

		Resolution resolution = POLICY.resolve(input(S1, base, myDeletion, theirDeletion, siblings, theirDeletion));

		assertTrue(resolution.deleted());
		assertEquals(List.of(deletionOf(S2, theirCopy)), resolution.extra());
		ConflictNote note = resolution.note();
		assertEquals(Kind.DELETE_WINS, note.kind());
		assertEquals(OTHER, note.affected());
		assertEquals(party(myDeletion), note.other());
		assertEquals(theirCopy.id(), note.dropped());
	}

	@Test
	void cancelAgainstMoveFindsTheMoveBehindAThirdMachinesMerge() {
		// Deren ist die Zusammenführung eines dritten Rechners (eigener Speichervorgang); verschoben hat Rechner A
		Version base = version(THIRD, booking("x", "s1"));
		Version myDeletion = deletion(OWN, "save-cancel");
		Version moved = deletion(OTHER, "save-move");
		Version theirCopy = version(OTHER, "save-move", booking("x", "s2", b -> b.setSeatNumber(2)));
		Version merged = deletion(THIRD, "save-merge");

		Resolution resolution = POLICY.resolve(
				input(S1, base, myDeletion, merged, siblings(Map.entry(S2, theirCopy)), moved, merged));

		assertEquals(List.of(deletionOf(S2, theirCopy)), resolution.extra());
		assertEquals(OTHER, resolution.note().affected());
	}

	@Test
	void bothMovedKeepsTheirsAndDeletesMine() {
		Version base = version(THIRD, booking("x", "s1"));
		Version myDeletion = deletion(OWN, "save-mine");
		Version myCopy = version(OWN, "save-mine", booking("x", "s3", b -> {
			b.setRowLabel("B");
			b.setTableNumber(2);
			b.setSeatNumber(3);
		}));
		Version theirDeletion = deletion(OTHER, "save-theirs");
		Version theirCopy = version(OTHER, "save-theirs", booking("x", "s2", b -> b.setSeatNumber(2)));
		Map<UnitKey, Sibling> siblings = siblings(Map.entry(S2, theirCopy), Map.entry(S3, myCopy));

		Resolution resolution = POLICY.resolve(input(S1, base, myDeletion, theirDeletion, siblings, theirDeletion));

		assertTrue(resolution.deleted());
		assertEquals(List.of(deletionOf(S3, myCopy)), resolution.extra());
		ConflictNote note = resolution.note();
		assertEquals(Kind.FIELDS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(party(theirDeletion), note.other());
		assertEquals(myCopy.id(), note.dropped());
		assertEquals(1, note.fields().size());
		ConflictNote.FieldConflict place = note.fields().get(0);
		assertEquals("place", place.group());
		assertEquals("s3", place.mine().get("seatId").asText());
		assertEquals("B", place.mine().get("rowLabel").asText());
		assertEquals(3, place.mine().get("seatNumber").asInt());
		assertEquals("s2", place.theirs().get("seatId").asText());
		assertEquals(2, place.theirs().get("seatNumber").asInt());
		// nur die Gruppe "Platz", keine Kundendaten
		assertFalse(place.mine().has("lastName"));
	}

	@Test
	void bothMovedToTheSameSeatKeepsTheBookingWithoutNote() {
		// beide Rechner verschieben x von s1 nach s2: Sie sind sich einig, s2 gabelt für sich (gleiche Buchung)
		Version base = version(THIRD, booking("x", "s1"));
		Version myDeletion = deletion(OWN, "save-mine");
		Version theirDeletion = deletion(OTHER, "save-theirs");
		Version theirCopy = version(OTHER, "save-theirs", booking("x", "s2", b -> b.setSeatNumber(2)));
		// die eigene Version auf s2 hat verloren, liegt aber im Ordner von s2
		Sibling s2 = new Sibling(new UnitSnapshot(S2, theirCopy.id(), Status.OK, false, theirCopy.payload()),
				ChainStep.of(theirCopy), Set.of("save-theirs", "save-mine"));

		Resolution resolution = POLICY.resolve(input(S1, base, myDeletion, theirDeletion, Map.of(S2, s2), theirDeletion));

		assertTrue(resolution.deleted());
		assertNull(resolution.note());
		assertTrue(resolution.extra().isEmpty());
	}

	@Test
	void moveEditedAgainLaterIsStillRecognized() {
		// die verschobene Kopie auf s2 wurde nach dem Verschieben in einem späteren Speichervorgang geändert
		Version base = version(THIRD, booking("x", "s1"));
		Version myDeletion = deletion(OWN, "save-move");
		Version edited = version(OWN, "save-later", booking("x", "s2", b -> {
			b.setSeatNumber(2);
			b.setComment("später geändert");
		}));
		Version theirDeletion = deletion(OTHER, "save-cancel");
		Sibling s2 = new Sibling(new UnitSnapshot(S2, edited.id(), Status.OK, false, edited.payload()),
				ChainStep.of(edited), Set.of("save-move", "save-later"));

		Resolution resolution = POLICY.resolve(input(S1, base, myDeletion, theirDeletion, Map.of(S2, s2), theirDeletion));

		assertEquals(List.of(deletionOf(S2, edited)), resolution.extra());
		assertEquals(Kind.DELETE_WINS, resolution.note().kind());
		assertEquals(OWN, resolution.note().affected());
		assertEquals(edited.id(), resolution.note().dropped());
	}

	@Test
	void pendingOwnMovedCopyIsRecognized() {
		// die eigene Kopie auf s2 ist noch nicht beansprucht: Sie steht in keiner Kette, aber im Ordner
		Version base = version(THIRD, booking("x", "s1"));
		Version myDeletion = deletion(OWN, "save-move");
		Version pending = version(OWN, "save-move", booking("x", "s2", b -> b.setSeatNumber(2)));
		Version theirDeletion = deletion(OTHER, "save-cancel");
		Sibling s2 = new Sibling(new UnitSnapshot(S2, pending.id(), Status.OK, false, pending.payload()), null,
				Set.of("save-move"));

		Resolution resolution = POLICY.resolve(input(S1, base, myDeletion, theirDeletion, Map.of(S2, s2), theirDeletion));

		assertEquals(List.of(deletionOf(S2, pending)), resolution.extra());
		assertEquals(Kind.DELETE_WINS, resolution.note().kind());
		assertEquals(OWN, resolution.note().affected());
	}

	@Test
	void bothCancelledIsSilent() {
		Version base = version(THIRD, booking("x", "s1"));
		Version myDeletion = deletion(OWN, "save-mine");
		Version theirDeletion = deletion(OTHER, "save-theirs");
		// x lebt noch auf s4, aber aus einem anderen Speichervorgang: keine Verschiebung dieser Löschungen
		Version elsewhere = version(THIRD, "save-older", booking("x", "s4"));
		Version unrelated = version(OTHER, "save-theirs", booking("z", "s3"));
		Map<UnitKey, Sibling> siblings = siblings(Map.entry(seat("s4"), elsewhere), Map.entry(S3, unrelated));

		Resolution resolution = POLICY.resolve(input(S1, base, myDeletion, theirDeletion, siblings, theirDeletion));

		assertTrue(resolution.deleted());
		assertNull(resolution.note());
		assertTrue(resolution.extra().isEmpty());
	}

	@Test
	void movedCopyThatIsDeletedAgainDoesNotCount() {
		// Die verschobene Einheit ist inzwischen gelöscht (im selben Speichervorgang kann das nicht sein, aber die
		// Kette kann darüber hinaus gewachsen sein): Sie gilt nicht als Verschiebung
		Version base = version(THIRD, booking("x", "s1"));
		Version myDeletion = deletion(OWN, "save-mine");
		Version theirDeletion = deletion(OTHER, "save-theirs");
		Version gone = deletion(OTHER, "save-theirs");

		Resolution resolution = POLICY.resolve(
				input(S1, base, myDeletion, theirDeletion, siblings(Map.entry(S2, gone)), theirDeletion));

		assertTrue(resolution.deleted());
		assertNull(resolution.note());
		assertTrue(resolution.extra().isEmpty());
	}

	// <-- Regel 0

	// Regel 4: gleiche Buchung -->

	@Test
	void sameBookingMergesByGroup() {
		Version base = version(THIRD, booking("x", "s1"));
		Version mine = version(OWN, booking("x", "s1", b -> b.setPaymentStatus(PaymentStatus.CASH)));
		Version theirs = version(OTHER, booking("x", "s1", b -> b.setComment("Rollstuhl mitbringen")));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertFalse(resolution.deleted());
		assertEquals(booking("x", "s1", b -> {
			b.setPaymentStatus(PaymentStatus.CASH);
			b.setComment("Rollstuhl mitbringen");
		}), resolution.payload());
		assertNull(resolution.note());
		assertTrue(resolution.extra().isEmpty());
	}

	@Test
	void sameBookingConflictingCustomerReportsCustomerGroup() {
		Version base = version(THIRD, booking("x", "s1"));
		Version mine = version(OWN, booking("x", "s1", b -> b.setLastName("Meier")));
		Version theirs = version(OTHER, booking("x", "s1", b -> b.setLastName("Schulz")));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty());
		ConflictNote note = resolution.note();
		assertEquals(Kind.FIELDS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(party(theirs), note.other());
		assertEquals(mine.id(), note.dropped());
		assertEquals(1, note.fields().size());
		assertEquals("customer", note.fields().get(0).group());
		assertEquals("Meier", note.fields().get(0).mine().get("lastName").asText());
		assertEquals("Schulz", note.fields().get(0).theirs().get("lastName").asText());
	}

	// <-- Regel 4
}
