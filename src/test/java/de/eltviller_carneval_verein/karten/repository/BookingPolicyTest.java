package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

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
import de.eltviller_carneval_verein.karten.store.ConflictInput.Sibling;
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

	/** Die Einheit {@code _m} mit ihrem Stand als Kette bis {@code current} und den Speichervorgängen in ihrem Ordner. */
	private static Sibling unassignedWith(UnitKey unit, Version current, String... saveIds) {
		return new Sibling(new UnitSnapshot(unit, current.id(), Status.OK, current.deleted(), current.payload()),
				ChainStep.of(current), Set.of(saveIds));
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
				Map.of(unassigned, unassignedWith(unassigned, theirDeletion, "save-0", "save-a", "save-b")), theirS1));
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
				Map.of(unassigned, unassignedWith(unassigned, mMerge, "save-0", "save-a", "save-b", "resolver-2")),
				theirS1));
		assertCancellationWins(s1Then, theirS1, myS1);
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
				Map.of(unassigned, unassignedWith(unassigned, theirDeletion, "save-0", "save-a", "save-b"), s4,
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
				Map.of(unassigned, unassignedWith(unassigned, mMerge, "save-0", "save-a", "save-b", "resolver-2"), s4,
						sibling(s4, theirS4)),
				theirS1));
		assertForeignMoveKeptOnSeat(s1Then, theirS1);
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
				Map.of(unassigned, unassignedWith(unassigned, earlierDeletion, "save-0", "save-early")), theirS1));

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
