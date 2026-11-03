package de.eltviller_carneval_verein.karten.repository;

import static de.eltviller_carneval_verein.karten.repository.TrailFixture.OTHER;
import static de.eltviller_carneval_verein.karten.repository.TrailFixture.OWN;
import static de.eltviller_carneval_verein.karten.repository.TrailFixture.THIRD;
import static de.eltviller_carneval_verein.karten.repository.TrailFixture.booking;
import static de.eltviller_carneval_verein.karten.repository.TrailFixture.seat;
import static de.eltviller_carneval_verein.karten.repository.TrailFixture.to;
import static de.eltviller_carneval_verein.karten.repository.TrailFixture.unassigned;
import static de.eltviller_carneval_verein.karten.repository.TrailFixture.withoutPlace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.repository.BookingTrail.Kind;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionMarks;

/** Die Entscheidungstabelle E1–E12 der Spec KARV-93 und E13 (KARV-124), je Zeile. A = fremd (OTHER), B = eigen (OWN). */
class BookingFateTest {

	private static final UnitKey M = unassigned("m");
	private static final UnitKey S1 = seat("s1");
	private static final UnitKey S3 = seat("s3");
	private static final UnitKey S4 = seat("s4");
	private static final UnitKey S5 = seat("s5");

	private final TrailFixture world = new TrailFixture();
	private final Version base = world.version(THIRD, null, withoutPlace("m", b -> {
	}));

	// Bauteile -->

	private BookingTrail kept(String machine, ObjectNode payload) {
		return new BookingTrail(Kind.KEPT, null, world.version(machine, base, payload), M);
	}

	private BookingTrail cancelled(String machine) {
		Version removal = world.version(machine, base, null);
		return new BookingTrail(Kind.CANCELLED, removal, removal, M);
	}

	private BookingTrail arrived(String machine, UnitKey end, ObjectNode payload) {
		return new BookingTrail(Kind.ARRIVED, world.version(machine, base, null, to(end)),
				world.version(machine, null, payload), end);
	}

	private BookingTrail failed(String machine, UnitKey end, ObjectNode payload) {
		return new BookingTrail(Kind.FAILED, world.version(machine, base, null, to(end)),
				world.version(machine, null, payload), end);
	}

	private static final BookingTrail UNTOUCHED = new BookingTrail(Kind.UNTOUCHED, null, null, null);

	private BookingFate decide(BookingTrail own, BookingTrail foreign, boolean ownWinsSource) {
		return BookingFate.decide("m", M, base, own, foreign, ownWinsSource, OWN, true, false);
	}

	private static ConflictNote only(BookingFate fate, UnitKey writtenAt) {
		assertEquals(1, fate.notes().size(), fate.notes().toString());
		assertEquals(writtenAt, fate.notes().get(0).writtenAt());
		return fate.notes().get(0).note();
	}

	// <-- Bauteile

	@Test
	void e1BothCancelledLeavesNothing() {
		BookingFate fate = decide(cancelled(OWN), cancelled(OTHER), false);

		assertNull(fate.carrier());
		assertEquals(List.of(), fate.deletions());
		assertEquals(List.of(), fate.notes());
	}

	@Test
	void e2CancellationBeatsAnEditWithNoteToTheEditor() {
		BookingTrail edit = kept(OTHER, withoutPlace("m", b -> b.setComment("neu")));
		BookingFate fate = decide(cancelled(OWN), edit, true);

		assertNull(fate.carrier());
		ConflictNote note = only(fate, M);
		assertEquals(ConflictNote.Kind.DELETE_WINS, note.kind());
		assertEquals(OTHER, note.affected());
		assertEquals(edit.last().id(), note.dropped());
	}

	@Test
	void e2WithoutChangeNeedsNoNote() {
		BookingFate fate = decide(cancelled(OWN), kept(OTHER, base.payload().deepCopy()), true);

		assertNull(fate.carrier());
		assertEquals(List.of(), fate.notes());
	}

	@Test
	void e3CancellationDeletesTheArrivedCopy() {
		BookingTrail move = arrived(OTHER, S4, booking("m", "s4"));
		BookingFate fate = decide(cancelled(OWN), move, false);

		assertNull(fate.carrier());
		assertEquals(List.of(S4), fate.deletions());
		ConflictNote note = only(fate, M);
		assertEquals(ConflictNote.Kind.DELETE_WINS, note.kind());
		assertEquals(OTHER, note.affected());
		assertEquals(S4, note.moved());
		assertEquals(move.last().id(), note.dropped());
	}

	@Test
	void e4CancellationBeatsAFailedMove() {
		BookingTrail move = failed(OWN, S1, booking("m", "s1", b -> b.setEMail("neu@example.org")));
		BookingFate fate = decide(move, cancelled(OTHER), true);

		assertNull(fate.carrier());
		assertEquals(List.of(), fate.deletions());
		ConflictNote note = only(fate, M);
		assertEquals(ConflictNote.Kind.DELETE_WINS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(move.last().id(), note.dropped());
		assertEquals(S1, note.moved(), "die weichende Fassung liegt am gescheiterten Ziel; dort sucht das Wiederherstellen");
	}

	@Test
	void e5ArrivedMoveTakesTheEdit() {
		BookingFate fate = decide(kept(OWN, withoutPlace("m", b -> b.setPaymentStatus(PaymentStatus.CARD))),
				arrived(OTHER, S4, booking("m", "s4")), false);

		assertEquals(S4, fate.carrier());
		assertEquals("CARD", fate.content().get("paymentStatus").asText());
		assertEquals("s4", fate.content().get("seatId").asText());
		assertEquals(List.of(), fate.notes());
	}

	@Test
	void e5ContestedGroupGoesToTheCopyWithFieldsNote() {
		BookingFate fate = decide(kept(OWN, withoutPlace("m", b -> b.setComment("mein"))),
				arrived(OTHER, S4, booking("m", "s4", b -> b.setComment("dein"))), true);

		assertEquals("dein", fate.content().get("comment").asText());
		ConflictNote note = only(fate, M);
		assertEquals(ConflictNote.Kind.FIELDS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(S4, note.moved());
	}

	@Test
	void e6SameEndNeedsNothing() {
		BookingFate fate = decide(arrived(OWN, S4, booking("m", "s4")), arrived(OTHER, S4, booking("m", "s4")), true);

		assertEquals(S4, fate.carrier());
		assertNull(fate.content());
		assertEquals(List.of(), fate.deletions());
		assertEquals(List.of(), fate.notes());
	}

	@Test
	void e7TheMoveOfTheWinnerAtTheSourceStands() {
		BookingTrail mine = arrived(OWN, S1, booking("m", "s1", b -> b.setEMail("neu@example.org")));
		BookingFate fate = decide(mine, arrived(OTHER, S4, booking("m", "s4")), false);

		assertEquals(S4, fate.carrier());
		assertEquals(List.of(S1), fate.deletions());
		assertEquals("neu@example.org", fate.content().get("email").asText());
		assertEquals("s4", fate.content().get("seatId").asText());
		ConflictNote note = only(fate, M);
		assertEquals(ConflictNote.Kind.FIELDS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals("place", note.fields().get(0).group());
		assertEquals(mine.last().id(), note.dropped());

		BookingFate ownWins = decide(mine, arrived(OTHER, S4, booking("m", "s4")), true);
		assertEquals(S1, ownWins.carrier());
		assertEquals(List.of(S4), ownWins.deletions());
		assertEquals(OTHER, only(ownWins, M).affected());
	}

	@Test
	void e8IgnoresTheWinnerAtTheSource() {
		for (boolean ownWins : new boolean[] { true, false }) {
			BookingTrail mine = failed(OWN, S1, booking("m", "s1", b -> b.setEMail("neu@example.org")));
			BookingFate fate = decide(mine, arrived(OTHER, S4, booking("m", "s4")), ownWins);

			assertEquals(S4, fate.carrier());
			assertEquals("neu@example.org", fate.content().get("email").asText());
			assertEquals(List.of(), fate.deletions());
			ConflictNote note = only(fate, M);
			assertEquals(ConflictNote.Kind.FIELDS, note.kind());
			assertEquals(OWN, note.affected());
			assertEquals("place", note.fields().get(0).group());
			assertNull(note.moved(), "wie R31b: der Dialog folgt der Buchung über ihre Kennung");
		}
	}

	@Test
	void e9OnUnassignedGivesDoubleSale() {
		BookingTrail mine = failed(OWN, S1, booking("m", "s1", b -> b.setEMail("neu@example.org")));
		BookingFate fate = decide(mine, kept(OTHER, withoutPlace("m", b -> b.setPaymentStatus(PaymentStatus.CARD))),
				false);

		assertEquals(M, fate.carrier());
		assertEquals("CARD", fate.content().get("paymentStatus").asText());
		assertEquals("neu@example.org", fate.content().get("email").asText());
		assertTrue(fate.content().get("unassigned").asBoolean());
		ConflictNote note = only(fate, M);
		assertEquals(ConflictNote.Kind.DOUBLE_SALE, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(M, note.moved());
		assertEquals("s1", note.place().get("seatId").asText());
	}

	/** Haben beide dieselbe Gruppe geändert, nennt der Vermerk sie (FIELDS mit "Platz"), statt sie still weichen zu lassen. */
	@Test
	void e9OnUnassignedWithContestedGroupNotesIt() {
		BookingTrail mine = failed(OWN, S1, booking("m", "s1", b -> b.setComment("Fenster")));
		BookingFate fate = decide(mine, kept(OTHER, withoutPlace("m", b -> b.setComment("Gang"))), false);

		assertEquals(M, fate.carrier());
		assertEquals("Gang", fate.content().get("comment").asText(), "bei derselben Gruppe gilt die behaltene Fassung");
		ConflictNote note = only(fate, M);
		assertEquals(ConflictNote.Kind.FIELDS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(List.of("place", "comment"), note.fields().stream().map(ConflictNote.FieldConflict::group).toList());
		assertEquals("Fenster", note.fields().get(1).mine().get("comment").asText());
		assertEquals(mine.last().id(), note.dropped());
	}

	/** Die Koppelregel für Zahlung und Preis gilt auch beim Übernehmen der Felder eines gescheiterten Umzugs (E9). */
	@Test
	void e9CouplesPaymentAndPrice() {
		Version priced = world.version(THIRD, null, withoutPlace("m", b -> b.setPrice(2000)));
		BookingTrail mine = new BookingTrail(Kind.FAILED, world.version(OWN, priced, null, to(S1)),
				world.version(OWN, null, booking("m", "s1", b -> {
					b.setPrice(2000);
					b.setPaymentStatus(PaymentStatus.CASH);
					b.setPaidAmount(2000);
				})), S1);
		BookingTrail theirs = new BookingTrail(Kind.KEPT, null,
				world.version(OTHER, priced, withoutPlace("m", b -> b.setPrice(2500))), M);

		BookingFate fate = BookingFate.decide("m", M, priced, mine, theirs, false, OWN, true, false);

		assertEquals(2500, fate.content().get("price").asInt(), "der Preis von Deren gilt");
		assertEquals(2000, fate.content().get("paidAmount").asInt(), "die Zahlung bleibt");
		ConflictNote note = only(fate, M);
		assertTrue(note.fields().stream().anyMatch(field -> field.group().equals("price")), note.toString());
	}

	/** Die Koppelregel gilt auch beim Übertragen auf eine angekommene Kopie (E5). */
	@Test
	void e5CouplesPaymentAndPrice() {
		Version priced = world.version(THIRD, null, withoutPlace("m", b -> b.setPrice(2000)));
		BookingTrail mine = new BookingTrail(Kind.KEPT, null, world.version(OWN, priced, withoutPlace("m", b -> {
			b.setPrice(2000);
			b.setPaymentStatus(PaymentStatus.CASH);
			b.setPaidAmount(2000);
		})), M);
		BookingTrail theirs = new BookingTrail(Kind.ARRIVED, world.version(OTHER, priced, null, to(S4)),
				world.version(OTHER, null, booking("m", "s4", b -> b.setPrice(2500))), S4);

		BookingFate fate = BookingFate.decide("m", M, priced, mine, theirs, false, OWN, true, false);

		assertEquals(S4, fate.carrier());
		assertEquals(2500, fate.content().get("price").asInt());
		assertEquals(2000, fate.content().get("paidAmount").asInt());
		ConflictNote note = only(fate, M);
		assertEquals(List.of("price"), note.fields().stream().map(ConflictNote.FieldConflict::group).toList());
		assertEquals(S4, note.moved());
	}

	@Test
	void e9OnASeatRevivesTheSeatWithFieldsNote() {
		Version seated = world.version(THIRD, null, booking("m", "s3"));
		BookingTrail mine = new BookingTrail(Kind.FAILED, world.version(OWN, seated, null, to(S1)),
				world.version(OWN, null, booking("m", "s1", b -> b.setEMail("neu@example.org"))), S1);
		BookingTrail theirs = new BookingTrail(Kind.KEPT, null,
				world.version(OTHER, seated, booking("m", "s3", b -> b.setComment("Fenster"))), S3);

		BookingFate fate = BookingFate.decide("m", S3, seated, mine, theirs, true, OWN, true, false);

		assertEquals(S3, fate.carrier());
		assertEquals("s3", fate.content().get("seatId").asText());
		assertEquals("Fenster", fate.content().get("comment").asText());
		assertEquals("neu@example.org", fate.content().get("email").asText());
		ConflictNote note = only(fate, S3);
		assertEquals(ConflictNote.Kind.FIELDS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals("place", note.fields().get(0).group());
	}

	@Test
	void e9WithReplacedSourceGoesWithoutPlace() {
		Version seated = world.version(THIRD, null, booking("m", "s3"));
		BookingTrail mine = new BookingTrail(Kind.FAILED, world.version(OWN, seated, booking("y", "s3"), to(S1)),
				world.version(OWN, null, booking("m", "s1")), S1);
		BookingTrail theirs = new BookingTrail(Kind.KEPT, null,
				world.version(OTHER, seated, booking("m", "s3", b -> b.setComment("Fenster"))), S3);

		BookingFate fate = BookingFate.decide("m", S3, seated, mine, theirs, true, OWN, true, false);

		assertEquals(M, fate.carrier());
		assertTrue(fate.content().get("unassigned").asBoolean());
		assertEquals("Fenster", fate.content().get("comment").asText());
		assertEquals(ConflictNote.Kind.DOUBLE_SALE, only(fate, S3).kind());
	}

	@Test
	void e10GivesEachSideItsOwnNoteAtItsTarget() {
		BookingFate fate = decide(failed(OWN, S1, booking("m", "s1")), failed(OTHER, S4, booking("m", "s4")), true);

		assertEquals(M, fate.carrier());
		assertTrue(fate.content().get("unassigned").asBoolean());
		assertEquals(2, fate.notes().size());
		BookingFate.Placed own = fate.notes().stream().filter(p -> OWN.equals(p.note().affected())).findFirst()
				.orElseThrow();
		BookingFate.Placed other = fate.notes().stream().filter(p -> OTHER.equals(p.note().affected())).findFirst()
				.orElseThrow();
		assertEquals(S1, own.writtenAt());
		assertEquals(S4, other.writtenAt());
		assertEquals(ConflictNote.Kind.DOUBLE_SALE, own.note().kind());
		assertNull(own.note().other(), "die Gegenseite am Ziel setzt die Anwendung ein");
	}

	@Test
	void e11FailedMoveAloneGoesWithoutPlaceWithNoteAtTheTarget() {
		BookingFate fate = decide(failed(OWN, S1, booking("m", "s1", b -> b.setEMail("neu@example.org"))), UNTOUCHED,
				true);

		assertEquals(M, fate.carrier());
		assertEquals("neu@example.org", fate.content().get("email").asText());
		ConflictNote note = only(fate, S1);
		assertEquals(ConflictNote.Kind.DOUBLE_SALE, note.kind());
		assertEquals(OWN, note.affected());
	}

	@Test
	void e12BothKeptIsRuleFour() {
		assertNull(decide(kept(OWN, base.payload().deepCopy()), kept(OTHER, base.payload().deepCopy()), true));
	}

	@Test
	void untouchedOtherSideNeedsNoDecisionExceptE11() {
		assertNull(decide(cancelled(OWN), UNTOUCHED, true));
		assertNull(decide(arrived(OWN, S4, booking("m", "s4")), UNTOUCHED, true));
		assertNull(decide(UNTOUCHED, kept(OTHER, base.payload().deepCopy()), true));
	}

	/**
	 * E13 (KARV-124): Ein Storno an der Quelle, die andere Seite unberührt, aber eine Auflösung hat die Buchung unverändert
	 * "ohne Platz" geparkt (Doppelverkauf): Die geparkte Kopie fällt weg, ohne Vermerk.
	 */
	@Test
	void e13CancellationDeletesTheParkedCopy() {
		BookingFate fate = decideAtS3(cancelled(OWN), UNTOUCHED, true);

		assertNull(fate.carrier());
		assertEquals(List.of(M), fate.deletions());
		assertTrue(fate.notes().isEmpty(), fate.notes().toString());
	}

	/** E13: Ein angekommener Umzug gilt gegen die geparkte Kopie; sie fällt weg, die Buchung lebt am Ziel wie dort. */
	@Test
	void e13ArrivedMoveDeletesTheParkedCopy() {
		BookingFate fate = decideAtS3(UNTOUCHED, arrived(OTHER, S4, booking("m", "s4")), true);

		assertEquals(S4, fate.carrier());
		assertNull(fate.content(), "der Inhalt am Ziel bleibt, wie er ist");
		assertEquals(List.of(M), fate.deletions());
		assertTrue(fate.notes().isEmpty(), fate.notes().toString());
	}

	/** E13: Endet der Umzug in der Einheit der geparkten Kopie selbst, lebt die Buchung dort; nichts fällt weg. */
	@Test
	void e13MoveToTheParkedUnitDeletesNothing() {
		assertNull(decideAtS3(arrived(OWN, M, withoutPlace("m", b -> {
		})), UNTOUCHED, true));
	}

	/**
	 * E13 greift nur mit geparkter Kopie und nur gegen eine unberührte Seite; sonst entscheidet die Tabelle wie bisher, und
	 * die Kopie bleibt.
	 */
	@Test
	void e13NeedsAParkedCopyAndAnUntouchedSide() {
		assertNull(decideAtS3(cancelled(OWN), UNTOUCHED, false));
		BookingTrail edit = new BookingTrail(Kind.KEPT, null,
				world.version(OTHER, null, booking("m", "s3", b -> b.setComment("neu"))), S3);
		assertEquals(List.of(), decideAtS3(cancelled(OWN), edit, true).deletions());
	}

	/** E13 an einem Sitz: Quelle S3, die geparkte Kopie ist M. */
	private BookingFate decideAtS3(BookingTrail own, BookingTrail foreign, boolean parked) {
		return decideAtS3(own, foreign, false, parked);
	}

	private BookingFate decideAtS3(BookingTrail own, BookingTrail foreign, Boolean ownWins, boolean parked) {
		Version seated = world.version(THIRD, null, booking("m", "s3"));
		return BookingFate.decide("m", S3, seated, own, foreign, ownWins, OWN, true, parked);
	}

	// Behalten an _<ID> (KARV-98, Spec 3d) -->

	/** Die Parkversion: eine Auflösung hat m von S3 nach M geparkt. */
	private Version parking() {
		return world.version(OWN, null, withoutPlace("m", b -> {
		}), VersionMarks.RESOLUTION.withMovedFrom(S3));
	}

	/** A hat die geparkte Kopie an M geändert (Parkweg). */
	private BookingTrail keptAtTheCopy(String comment) {
		Version parking = parking();
		Version edit = world.version(OTHER, parking, withoutPlace("m", b -> b.setComment(comment)));
		return new BookingTrail(Kind.KEPT, null, edit, M, edit, parking);
	}

	@Test
	void e2CancellationDeletesAKeptCopyElsewhereWithMovedThere() {
		BookingFate fate = decideAtS3(cancelled(OWN), keptAtTheCopy("A"), false);

		assertNull(fate.carrier());
		assertEquals(List.of(M), fate.deletions());
		ConflictNote note = only(fate, S3);
		assertEquals(ConflictNote.Kind.DELETE_WINS, note.kind());
		assertEquals(OTHER, note.affected());
		assertEquals(M, note.moved(), "dort liegt die weichende Fassung");
	}

	@Test
	void e5ArrivedMoveDeletesTheKeptCopyElsewhere() {
		BookingFate fate = decideAtS3(arrived(OWN, S4, booking("m", "s4")), keptAtTheCopy("A"), false);

		assertEquals(S4, fate.carrier());
		assertEquals(List.of(M), fate.deletions());
		assertEquals("A", fate.content().get("comment").asText(), "A's Änderung kommt mit");
		assertEquals("s4", fate.content().get("seatId").asText(), "der Platz bleibt der des Umzugs");
	}

	@Test
	void e9KeptElsewhereStaysCarrierEvenWithAFreeSource() {
		BookingFate fate = decideAtS3(failed(OWN, S1, booking("m", "s1")), keptAtTheCopy("A"), false);

		assertEquals(M, fate.carrier());
		assertTrue(fate.content().get("unassigned").asBoolean(), "ohne Platz, nicht auf dem Sitz der Quelle");
		assertEquals(List.of(), fate.deletions());
	}

	/** Gilt an der Quelle keine Nutzer-Version (nur die Auflösung), gewinnt die Seite mit dem Parkweg, in beiden Rollen. */
	@Test
	void e7ParkingSideWinsWhenNoUserVersionStandsAtTheSource() {
		Version parking = parking();
		Version moved = world.version(OTHER, parking, null, to(S5));
		BookingTrail viaParking = new BookingTrail(Kind.ARRIVED, moved, world.version(OTHER, null, booking("m", "s5")), S5,
				null, parking);
		BookingTrail stale = arrived(OWN, S4, booking("m", "s4"));

		BookingFate foreignParks = decideAtS3(stale, viaParking, null, false);
		BookingFate ownParks = decideAtS3(viaParking, stale, null, false);

		assertEquals(S5, foreignParks.carrier());
		assertEquals(List.of(S4), foreignParks.deletions());
		assertEquals(S5, ownParks.carrier());
		assertEquals(List.of(S4), ownParks.deletions());
	}

	@Test
	void e12KeptAtSourceAgainstKeptElsewhereIsRuleFour() {
		BookingTrail atSource = new BookingTrail(Kind.KEPT, null,
				world.version(OWN, null, booking("m", "s3", b -> b.setComment("B"))), S3);

		assertNull(decideAtS3(atSource, keptAtTheCopy("A"), false));
	}
}
