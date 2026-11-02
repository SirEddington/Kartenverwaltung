package de.eltviller_carneval_verein.karten.repository;

import static de.eltviller_carneval_verein.karten.repository.TrailFixture.OTHER;
import static de.eltviller_carneval_verein.karten.repository.TrailFixture.OWN;
import static de.eltviller_carneval_verein.karten.repository.TrailFixture.THIRD;
import static de.eltviller_carneval_verein.karten.repository.TrailFixture.booking;
import static de.eltviller_carneval_verein.karten.repository.TrailFixture.from;
import static de.eltviller_carneval_verein.karten.repository.TrailFixture.seat;
import static de.eltviller_carneval_verein.karten.repository.TrailFixture.to;
import static de.eltviller_carneval_verein.karten.repository.TrailFixture.unassigned;
import static de.eltviller_carneval_verein.karten.repository.TrailFixture.withoutPlace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.repository.BookingTrail.Kind;
import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionMarks;

class BookingTrailTest {

	private static final UnitKey M = unassigned("m");
	private static final UnitKey S1 = seat("s1");
	private static final UnitKey S4 = seat("s4");
	private static final UnitKey S5 = seat("s5");

	private final TrailFixture world = new TrailFixture();
	/** m ohne Platz, von einem dritten Rechner. */
	private final Version base = world.claim(M, world.version(THIRD, null, withoutPlace("m", b -> {
	})));

	private BookingTrail own(ConflictInput input) {
		return BookingTrail.of(input, "m", M, base.id(), true);
	}

	private BookingTrail foreign(ConflictInput input) {
		return BookingTrail.of(input, "m", M, base.id(), false);
	}

	/** A (fremd) verschiebt m nach s4, die Löschung gilt auf {@code _m}. */
	private Version foreignMoveToS4() {
		Version deletion = world.claim(M, world.version(OTHER, "save-a", base, null, to(S4)));
		world.claim(S4, world.version(OTHER, "save-a", null, booking("m", "s4"), from(M)));
		return deletion;
	}

	@Test
	void untouchedWithoutOwnVersions() {
		Version theirs = foreignMoveToS4();
		Version mine = world.beside(M, world.version(OWN, base, withoutPlace("m", b -> b.setComment("neu"))));

		ConflictInput input = world.input(M, base, List.of(mine));

		assertEquals(Kind.KEPT, own(input).kind());
		assertSame(mine, own(input).last());
		assertEquals(Kind.ARRIVED, foreign(input).kind());
		assertSame(theirs, foreign(input).removal());
		assertEquals(Kind.UNTOUCHED, BookingTrail.of(input, "m", S1, null, false).kind());
	}

	@Test
	void cancelledWithoutMovedTo() {
		foreignMoveToS4();
		Version mine = world.beside(M, world.version(OWN, base, null));

		BookingTrail trail = own(world.input(M, base, List.of(mine)));

		assertEquals(Kind.CANCELLED, trail.kind());
		assertSame(mine, trail.removal());
		assertSame(mine, trail.last());
		assertEquals(M, trail.end());
	}

	@Test
	void cancelledByResaleWithoutMovedTo() {
		Version seated = world.claim(S1, world.version(THIRD, null, booking("m", "s1")));
		Version theirs = world.claim(S1, world.version(OTHER, seated, booking("m", "s1", b -> b.setComment("x"))));
		Version mine = world.beside(S1, world.version(OWN, seated, booking("y", "s1")));

		BookingTrail trail = BookingTrail.of(world.input(S1, seated, List.of(mine)), "m", S1, seated.id(), true);

		assertEquals(Kind.CANCELLED, trail.kind());
		assertSame(mine, trail.removal());
		assertEquals(Kind.KEPT,
				BookingTrail.of(world.input(S1, seated, List.of(mine)), "m", S1, seated.id(), false).kind());
		assertSame(theirs, BookingTrail.of(world.input(S1, seated, List.of(mine)), "m", S1, seated.id(), false).last());
	}

	@Test
	void arrivedFollowsTheChain() {
		foreignMoveToS4();
		world.claim(S4, world.version(OTHER, "save-d", world.chain(S4).get(0), null, to(S5)));
		Version atS5 = world.claim(S5, world.version(OTHER, "save-d", null, booking("m", "s5"), from(S4)));
		Version mine = world.beside(M, world.version(OWN, base, null, to(S1)));
		world.claim(S1, world.version(OTHER, null, booking("t", "s1")));

		BookingTrail trail = foreign(world.input(M, base, List.of(mine)));

		assertEquals(Kind.ARRIVED, trail.kind());
		assertEquals(S5, trail.end());
		assertSame(atS5, trail.last());
	}

	@Test
	void arrivedAlsoWhenTheOtherSideWonTheTarget() {
		foreignMoveToS4();
		Version mine = world.beside(M, world.version(OWN, base, null, to(S4)));
		world.beside(S4, world.version(OWN, null, booking("m", "s4"), from(M)));

		BookingTrail trail = own(world.input(M, base, List.of(mine)));

		assertEquals(Kind.ARRIVED, trail.kind());
		assertEquals(S4, trail.end());
	}

	@Test
	void failedWhenAnotherBookingWonTheTarget() {
		foreignMoveToS4();
		Version mine = world.beside(M, world.version(OWN, base, null, to(S1)));
		world.claim(S1, world.version(OTHER, null, booking("t", "s1")));
		Version atS1 = world.beside(S1, world.version(OWN, null, booking("m", "s1", b -> b.setEMail("neu@example.org")),
				from(M)));

		BookingTrail trail = own(world.input(M, base, List.of(mine)));

		assertEquals(Kind.FAILED, trail.kind());
		assertEquals(S1, trail.end());
		assertSame(atS1, trail.last());
		assertSame(mine, trail.removal());
	}

	/** Trägt das Ziel die Buchung, aber nicht aus diesem Umzug (etwa wiederhergestellt), ist der Umzug nicht angekommen. */
	@Test
	void arrivalNeedsTheMoveFromTheSource() {
		foreignMoveToS4();
		Version mine = world.beside(M, world.version(OWN, base, null, to(S1)));
		world.claim(S1, world.version(OTHER, null, booking("m", "s1")));
		world.beside(S1, world.version(OWN, null, booking("m", "s1"), from(M)));

		assertEquals(Kind.FAILED, own(world.input(M, base, List.of(mine))).kind());
	}

	@Test
	void failedUsesTheWholeStretchAtTheTarget() {
		foreignMoveToS4();
		Version mine = world.beside(M, world.version(OWN, "save-a", base, null, to(S1)));
		Version t = world.claim(S1, world.version(OTHER, null, booking("t", "s1")));
		Version first = world.beside(S1, world.version(OWN, "save-a", null, booking("m", "s1"), from(M)));
		Version second = world.beside(S1, world.version(OWN, "save-c", first, booking("m", "s1", b -> b.setComment("Fenster")),
				VersionMarks.NONE));

		ConflictInput atTarget = world.input(S1, null, List.of(first, second));

		assertEquals(t, atTarget.theirs());
		BookingTrail trail = BookingTrail.of(atTarget, "m", M, BookingTrail.sourceBase(atTarget, M, first), true);
		assertEquals(Kind.FAILED, trail.kind());
		assertSame(second, trail.last());
		assertSame(mine, trail.removal());
	}

	@Test
	void cancelledAfterArrival() {
		foreignMoveToS4();
		Version cancel = world.claim(S4, world.version(OTHER, world.chain(S4).get(0), null));
		Version mine = world.beside(M, world.version(OWN, base, withoutPlace("m", b -> b.setComment("neu"))));

		BookingTrail trail = foreign(world.input(M, base, List.of(mine)));

		assertEquals(Kind.CANCELLED, trail.kind());
		assertEquals(S4, trail.end());
		assertSame(cancel, trail.last());
	}

	@Test
	void resolutionVersionsAreSkipped() {
		foreignMoveToS4();
		world.claim(S4, world.version(OWN, world.chain(S4).get(0), null, VersionMarks.RESOLUTION));
		Version mine = world.beside(M, world.version(OWN, base, withoutPlace("m", b -> b.setComment("neu"))));

		BookingTrail trail = foreign(world.input(M, base, List.of(mine)));

		assertEquals(Kind.ARRIVED, trail.kind());
		assertEquals(S4, trail.end());
	}

	@Test
	void moveBackEnds() {
		Version seated = world.claim(S1, world.version(THIRD, null, booking("m", "s1")));
		Version away = world.claim(S1, world.version(OTHER, "save-a", seated, null, to(seat("s2"))));
		Version atS2 = world.claim(seat("s2"), world.version(OTHER, "save-a", null, booking("m", "s2"), from(S1)));
		world.claim(seat("s2"), world.version(OTHER, "save-b", atS2, null, to(S1)));
		Version back = world.claim(S1, world.version(OTHER, "save-b", away, booking("m", "s1"), from(seat("s2"))));
		Version mine = world.beside(S1, world.version(OWN, seated, booking("m", "s1", b -> b.setComment("neu"))));

		BookingTrail trail = BookingTrail.of(world.input(S1, seated, List.of(mine)), "m", S1, seated.id(), false);

		assertEquals(Kind.ARRIVED, trail.kind());
		assertEquals(S1, trail.end());
		assertSame(back, trail.last());
	}

	@Test
	void ownPendingVersionAtTargetCountsAsArrived() {
		Version theirs = world.beside(M, world.version(OTHER, base, withoutPlace("m", b -> b.setComment("x"))));
		Version mine = world.claim(M, world.version(OWN, base, null, to(S1)));
		Version pending = world.beside(S1, world.version(OWN, null, booking("m", "s1"), from(M)));
		world.current(S1, pending);

		BookingTrail trail = own(world.input(M, base, List.of(theirs)));

		assertEquals(Kind.ARRIVED, trail.kind());
		assertEquals(S1, trail.end());
		assertSame(mine, trail.removal());
	}

	@Test
	void unreadableVersionOnTheTrailIsNotACancellation() {
		foreignMoveToS4();
		// ein Inhalt, der nicht zur Einheit passt (Sitz-ID auf einer Einheit ohne Platz): unlesbar
		Version mine = world.beside(M, world.version(OWN, base, booking("m", "s9")));

		BookingTrail trail = own(world.input(M, base, List.of(mine)));

		assertEquals(Kind.UNTOUCHED, trail.kind());
	}

	/**
	 * Drei Rechner: An der Quelle liegen zwei fremde Absichten (ein Umzug, der dort verloren hat, und ein Storno, das gilt).
	 * Die fremde Seite ist die, die in der Kette steht; die andere ist zwischen den Fremden schon aufgelöst.
	 */
	@Test
	void foreignSideIsTheOneInTheChain() {
		Version move = world.beside(M, world.version(THIRD, "save-c", base, null, to(S4)));
		world.claim(S4, world.version(THIRD, "save-c", null, booking("m", "s4"), from(M)));
		world.claim(M, world.version(OTHER, base, null));
		Version mine = world.beside(M, world.version(OWN, base, withoutPlace("m", b -> b.setComment("neu"))));

		BookingTrail trail = foreign(world.input(M, base, List.of(mine)));

		assertEquals(Kind.CANCELLED, trail.kind(), "nicht der ältere Umzug " + move.id());
	}

	@Test
	void ownWinsAtTellsWhoseVersionStandsAtTheSource() {
		foreignMoveToS4();
		Version mine = world.beside(M, world.version(OWN, base, null, to(S1)));
		ConflictInput input = world.input(M, base, List.of(mine));

		assertEquals(Boolean.FALSE, BookingTrail.ownWinsAt(input, M, base.id()));
		assertNull(BookingTrail.ownWinsAt(input, S5, null));
	}
}
