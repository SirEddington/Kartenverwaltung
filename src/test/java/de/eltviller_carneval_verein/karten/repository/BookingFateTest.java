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

/** Die Entscheidungstabelle E1–E12 der Spec KARV-93, je Zeile. A = fremd (OTHER), B = eigen (OWN). */
class BookingFateTest {

	private static final UnitKey M = unassigned("m");
	private static final UnitKey S1 = seat("s1");
	private static final UnitKey S3 = seat("s3");
	private static final UnitKey S4 = seat("s4");

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
		return BookingFate.decide("m", M, base, own, foreign, ownWinsSource, OWN);
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
			assertEquals(S4, note.moved());
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

	@Test
	void e9OnASeatRevivesTheSeatWithFieldsNote() {
		Version seated = world.version(THIRD, null, booking("m", "s3"));
		BookingTrail mine = new BookingTrail(Kind.FAILED, world.version(OWN, seated, null, to(S1)),
				world.version(OWN, null, booking("m", "s1", b -> b.setEMail("neu@example.org"))), S1);
		BookingTrail theirs = new BookingTrail(Kind.KEPT, null,
				world.version(OTHER, seated, booking("m", "s3", b -> b.setComment("Fenster"))), S3);

		BookingFate fate = BookingFate.decide("m", S3, seated, mine, theirs, true, OWN);

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

		BookingFate fate = BookingFate.decide("m", S3, seated, mine, theirs, true, OWN);

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
}
