package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.UnitMapper.Assembly;
import de.eltviller_carneval_verein.karten.repository.UnitMapper.UnitPayload;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.store.VersionId;

class UnitMapperTest {

	private static final ObjectMapper MAPPER = JsonMapperFactory.create();
	private static final VersionId VERSION = VersionId.create(1_800_000_000_000L, "abcdefgh", 1);

	// Bauteile -->

	private static Booking booking(Presentation p, String seatId, String rowLabel, int table, int seat, String lastName) {
		Booking booking = p.addBooking();
		booking.setSeatId(seatId);
		booking.setRowLabel(rowLabel);
		booking.setTableNumber(table);
		booking.setSeatNumber(seat);
		booking.setLastName(lastName);
		return booking;
	}

	private static Booking emptyBooking(Presentation p, String seatId, int seat) {
		return booking(p, seatId, "A", 1, seat, null);
	}

	/** Event mit 2 Vorstellungen, 3 Buchungen mit Daten und 2 leeren. */
	private static Event simpleEvent() {
		Event event = new Event("11111111-1111-1111-1111-111111111111");
		event.setName("Prunksitzung");
		Presentation first = new Presentation("22222222-2222-2222-2222-222222222221");
		first.setName("Freitag");
		Presentation second = new Presentation("22222222-2222-2222-2222-222222222222");
		second.setName("Samstag");
		event.setPresentations(new ArrayList<>(List.of(first, second)));

		booking(first, "seat-a1", "A", 1, 1, "Müller");
		emptyBooking(first, "seat-a2", 2);
		booking(first, "seat-a3", "A", 1, 3, "Meier");
		emptyBooking(second, "seat-a1", 1);
		booking(second, "seat-a1x", "A", 1, 1, "Schmitz");
		return event;
	}

	/** Ein Event mit Daten, Zeiten, Preisen, Zahlstatus und Abholdatum, wie es ein Verein pflegt. */
	private static Event realisticEvent() {
		Event event = new Event("33333333-3333-3333-3333-333333333333");
		event.setName("Prunksitzung 2027");
		event.setDescription("Große Sitzung mit \"Umlauten\": äöüß");
		Presentation presentation = event.addPresentation("Freitag");
		presentation.setDate(LocalDate.of(2027, 2, 5));
		presentation.setTime(LocalTime.of(19, 30));
		presentation.setPrice(2500);
		presentation.setHallId("44444444-4444-4444-4444-444444444444");
		presentation.setSeatingPlanId("55555555-5555-5555-5555-555555555555");
		presentation.setActualCashAmount(123_450);
		presentation.setCashDifferenceComment("2,50 Euro Wechselgeld");
		Presentation second = event.addPresentation("Samstag");
		second.setDate(LocalDate.of(2027, 2, 6));
		second.setTime(LocalTime.of(14, 0, 30));
		second.setPrice(0);

		Booking paid = booking(presentation, "seat-1", "A", 3, 2, "Müller");
		paid.setFirstName("Anna");
		paid.setEMail("anna@example.de");
		paid.setComment("Rollator");
		paid.setPrice(1800);
		paid.setPaymentStatus(PaymentStatus.CARD);
		paid.setCollected(true);
		paid.setReservationDate(LocalDate.of(2026, 10, 1));
		paid.setPaymentDate(LocalDate.of(2026, 10, 2));
		paid.setCollectionDate(LocalDate.of(2027, 2, 5));
		paid.setCategory("Premium");
		paid.setWheelchairAccessible(true);
		Booking reserved = booking(presentation, "seat-2", "AB", 12, 4, "Schmitz");
		reserved.setReservationDate(LocalDate.of(2026, 10, 3));
		Booking lost = booking(second, null, "", 0, 0, "Ohne Platz");
		lost.setUnassigned(true);
		return event;
	}

	private static Hall realisticHall() {
		Hall hall = new Hall("66666666-6666-6666-6666-666666666666");
		hall.setName("Stadthalle");
		hall.setDescription("Große Halle");
		hall.setHallWidth(24.0);
		hall.setHallHeight(17.5);
		hall.addSeatingPlan();
		hall.addHallObject();
		return hall;
	}

	private static UnitSnapshot snapshot(UnitPayload part) {
		return new UnitSnapshot(part.unit(), VERSION, Status.OK, false, part.payload());
	}

	private static List<UnitSnapshot> snapshots(List<UnitPayload> parts) {
		return parts.stream().map(UnitMapperTest::snapshot).toList();
	}

	/** Schreibt die Daten als Text und liest sie wieder, wie es die Versionsdatei tut. */
	private static ObjectNode reread(ObjectNode payload) throws IOException {
		return (ObjectNode) MAPPER.readTree(MAPPER.writeValueAsString(payload));
	}

	private static UnitSnapshot withState(UnitSnapshot snapshot, Status status, boolean deleted) {
		return new UnitSnapshot(snapshot.unit(), snapshot.version(), status, deleted, snapshot.payload());
	}

	private static UnitSnapshot noVersion(UnitKey unit, Status status) {
		return new UnitSnapshot(unit, null, status, false, null);
	}

	private static UnitSnapshot find(List<UnitSnapshot> snapshots, UnitKey unit) {
		return snapshots.stream().filter(s -> s.unit().equals(unit)).findFirst().orElseThrow();
	}

	private static List<UnitSnapshot> replace(List<UnitSnapshot> snapshots, UnitSnapshot replacement) {
		List<UnitSnapshot> result = new ArrayList<>(snapshots);
		result.removeIf(s -> s.unit().equals(replacement.unit()));
		result.add(replacement);
		return result;
	}

	private static List<UnitSnapshot> without(List<UnitSnapshot> snapshots, UnitKey unit) {
		List<UnitSnapshot> result = new ArrayList<>(snapshots);
		result.removeIf(s -> s.unit().equals(unit));
		return result;
	}

	// <-- Bauteile

	// Booking.id -->

	@Test
	void bookingIdSurvivesJsonRoundTrip() throws IOException {
		Booking booking = new Booking();
		booking.setLastName("Müller");

		Booking loaded = MAPPER.readValue(MAPPER.writeValueAsString(booking), Booking.class);

		assertNotNull(booking.getId());
		assertEquals(booking.getId(), loaded.getId());
	}

	@Test
	void newBookingsGetDistinctIds() {
		Set<String> ids = new HashSet<>();
		for (int i = 0; i < 100; i++) {
			ids.add(new Booking().getId());
		}
		assertEquals(100, ids.size());
	}

	// <-- Booking.id

	// decompose -->

	@Test
	void decomposeSplitsEventIntoHeadPresentationsAndBookings() {
		Event event = simpleEvent();
		Presentation first = event.getPresentations().get(0);
		Presentation second = event.getPresentations().get(1);

		List<UnitPayload> parts = UnitMapper.decompose(event);

		// Kopf, dann je Vorstellung sie selbst und ihre Buchungen mit Daten; leere Buchungen fehlen
		assertEquals(List.of(UnitKey.event(event.getId()), UnitKey.presentation(event.getId(), first.getId()),
				UnitKey.booking(event.getId(), first.getId(), "seat-a1"),
				UnitKey.booking(event.getId(), first.getId(), "seat-a3"),
				UnitKey.presentation(event.getId(), second.getId()),
				UnitKey.booking(event.getId(), second.getId(), "seat-a1x")), parts.stream().map(UnitPayload::unit).toList());
		assertEquals(6, parts.size());
		assertEquals(1, parts.stream().filter(p -> p.unit().type() == UnitType.EVENT).count());
		assertEquals(2, parts.stream().filter(p -> p.unit().type() == UnitType.PRESENTATION).count());
		assertEquals(3, parts.stream().filter(p -> p.unit().type() == UnitType.BOOKING).count());

		ObjectNode head = parts.get(0).payload();
		assertFalse(head.has("presentations"));
		assertEquals("Prunksitzung", head.get("name").asText());
		assertEquals(event.getId(), head.get("id").asText());
		ObjectNode presentation = parts.get(1).payload();
		assertFalse(presentation.has("bookings"));
		assertEquals("Freitag", presentation.get("name").asText());
		assertEquals("Müller", parts.get(2).payload().get("lastName").asText());
	}

	@Test
	void decomposeDoesNotChangeTheEvent() {
		Event event = simpleEvent();
		Presentation first = event.getPresentations().get(0);

		UnitMapper.decompose(event);

		assertEquals(2, event.getPresentations().size());
		assertEquals(3, first.getBookings().size());
		assertSame(event, first.getParent());
	}

	@Test
	void unassignedBookingUsesItsId() {
		Event event = new Event("11111111-1111-1111-1111-111111111111");
		Presentation presentation = event.addPresentation("Freitag");
		Booking lost = booking(presentation, "seat-gone", "A", 1, 1, "Müller");
		lost.setUnassigned(true);
		Booking withoutSeat = booking(presentation, null, "", 0, 0, "Meier");

		List<UnitPayload> parts = UnitMapper.decompose(event);

		assertEquals(UnitKey.unassignedBooking(event.getId(), presentation.getId(), lost.getId()), parts.get(2).unit());
		assertEquals("_" + lost.getId(), parts.get(2).unit().bookingDir());
		assertEquals(UnitKey.unassignedBooking(event.getId(), presentation.getId(), withoutSeat.getId()),
				parts.get(3).unit());
	}

	@Test
	void duplicateSeatIsRejected() {
		Event event = new Event("11111111-1111-1111-1111-111111111111");
		Presentation presentation = event.addPresentation("Freitag");
		booking(presentation, "seat-1", "A", 3, 2, "Müller");
		booking(presentation, "seat-1", "A", 3, 2, "Meier");

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> UnitMapper.decompose(event));

		assertEquals("Zwei Buchungen belegen denselben Platz (Vorstellung \"Freitag\", Tisch A3, Sitz 2). "
				+ "Es wurde nichts gespeichert.", e.getMessage());
	}

	@Test
	void emptyDuplicateSeatIsNotAnError() {
		Event event = new Event("11111111-1111-1111-1111-111111111111");
		Presentation presentation = event.addPresentation("Freitag");
		booking(presentation, "seat-1", "A", 3, 2, "Müller");
		emptyBooking(presentation, "seat-1", 2);

		assertEquals(3, UnitMapper.decompose(event).size());
	}

	@Test
	void decomposeHallIsOneUnit() {
		Hall hall = realisticHall();

		UnitPayload part = UnitMapper.decompose(hall);

		assertEquals(UnitKey.hall(hall.getId()), part.unit());
		assertEquals("Stadthalle", part.payload().get("name").asText());
		assertTrue(part.payload().has("seatingPlans"));
	}

	// <-- decompose

	// assemble -->

	@Test
	void assembleIsInverseOfDecompose() {
		Event event = realisticEvent();
		List<UnitPayload> parts = UnitMapper.decompose(event);

		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots(parts));

		assertEquals(List.of(), assembly.warnings());
		assertEquals(1, assembly.items().size());
		Event loaded = assembly.items().get(0);
		assertEquals(UnitMapper.normalize(event), UnitMapper.normalize(loaded));
		assertEquals(event.getId(), loaded.getId());
		for (Presentation presentation : loaded.getPresentations()) {
			assertSame(loaded, presentation.getParent());
			for (Booking booking : presentation.getBookings()) {
				assertSame(presentation, booking.getParent());
			}
		}
		assertEquals(3, loaded.getBookings().size());
	}

	@Test
	void decomposeOfReloadedEventEqualsStoredPayload() throws IOException {
		Event event = realisticEvent();
		List<UnitPayload> stored = new ArrayList<>();
		for (UnitPayload part : UnitMapper.decompose(event)) {
			stored.add(new UnitPayload(part.unit(), reread(part.payload())));
		}

		Event loaded = UnitMapper.assembleEvents(snapshots(stored)).items().get(0);
		List<UnitPayload> again = UnitMapper.decompose(loaded);

		assertEquals(stored.size(), again.size());
		for (int i = 0; i < stored.size(); i++) {
			assertEquals(stored.get(i).unit(), again.get(i).unit());
			assertEquals(stored.get(i).payload(), again.get(i).payload(), "Einheit " + stored.get(i).unit());
		}
	}

	@Test
	void decomposeOfReloadedHallEqualsStoredPayload() throws IOException {
		UnitPayload stored = UnitMapper.decompose(realisticHall());
		ObjectNode reread = reread(stored.payload());

		Hall loaded = UnitMapper
				.assembleHalls(List.of(new UnitSnapshot(stored.unit(), VERSION, Status.OK, false, reread))).items().get(0);

		assertEquals(reread, UnitMapper.decompose(loaded).payload());
	}

	@Test
	void normalizeReadsNumbersAndDatesLikeAFile() throws IOException {
		Event event = realisticEvent();

		ObjectNode normalized = UnitMapper.normalize(event);

		assertEquals(reread(normalized), normalized);
		ObjectNode presentation = (ObjectNode) normalized.get("presentations").get(0);
		assertTrue(presentation.get("date").isTextual());
		assertEquals("2027-02-05", presentation.get("date").asText());
		assertEquals("19:30:00", presentation.get("time").asText());
		assertTrue(presentation.get("price").isInt());
		assertEquals("CARD", presentation.get("bookings").get(0).get("paymentStatus").asText());
	}

	@Test
	void assembleDoesNotChangeTheSnapshotPayloads() {
		List<UnitSnapshot> snapshots = snapshots(UnitMapper.decompose(realisticEvent()));
		List<ObjectNode> before = snapshots.stream().map(s -> s.payload().deepCopy()).toList();

		UnitMapper.assembleEvents(snapshots);

		for (int i = 0; i < snapshots.size(); i++) {
			assertEquals(before.get(i), snapshots.get(i).payload());
		}
	}

	@Test
	void presentationsAreSortedByDateTimeNameId() {
		Event event = new Event("11111111-1111-1111-1111-111111111111");
		// absichtlich durcheinander angelegt
		Presentation noDate = presentation(event, "aaaa", "Ohne Datum", null, null);
		Presentation noTime = presentation(event, "bbbb", "Ohne Zeit", LocalDate.of(2027, 2, 5), null);
		Presentation late = presentation(event, "cccc", "Spät", LocalDate.of(2027, 2, 5), LocalTime.of(20, 0));
		Presentation earlyB = presentation(event, "dddd", "beta", LocalDate.of(2027, 2, 5), LocalTime.of(19, 0));
		Presentation earlyA = presentation(event, "eeee", "Alpha", LocalDate.of(2027, 2, 5), LocalTime.of(19, 0));
		Presentation earlyA2 = presentation(event, "aaab", "alpha", LocalDate.of(2027, 2, 5), LocalTime.of(19, 0));
		Presentation earlier = presentation(event, "ffff", "Früher", LocalDate.of(2027, 1, 1), LocalTime.of(23, 0));
		Presentation noName = presentation(event, "gggg", null, LocalDate.of(2027, 2, 5), LocalTime.of(19, 0));
		List<UnitSnapshot> snapshots = new ArrayList<>(snapshots(UnitMapper.decompose(event)));
		Collections.shuffle(snapshots, new Random(42));

		Event loaded = UnitMapper.assembleEvents(snapshots).items().get(0);

		// gleiche Zeit und gleicher Name ohne Groß-/Kleinschreibung (Alpha/alpha): die ID entscheidet
		assertEquals(
				List.of(earlier, earlyA2, earlyA, earlyB, noName, late, noTime, noDate).stream().map(Presentation::getId)
						.toList(),
				loaded.getPresentations().stream().map(Presentation::getId).toList());
	}

	private static Presentation presentation(Event event, String idPart, String name, LocalDate date, LocalTime time) {
		Presentation presentation = new Presentation("pres-" + idPart);
		presentation.setName(name);
		presentation.setDate(date);
		presentation.setTime(time);
		event.getPresentations().add(presentation);
		presentation.setParent(event);
		return presentation;
	}

	@Test
	void bookingsAreInPlaceOrder() {
		Event event = new Event("11111111-1111-1111-1111-111111111111");
		Presentation presentation = event.addPresentation("Freitag");
		booking(presentation, "s-1", "B", 1, 1, "B1");
		booking(presentation, "s-2", "AA", 1, 1, "AA1");
		booking(presentation, "s-3", "A", 2, 1, "A2-1");
		booking(presentation, "s-4", "A", 1, 2, "A1-2");
		booking(presentation, "s-5", "A", 1, 1, "A1-1");
		List<UnitSnapshot> snapshots = new ArrayList<>(snapshots(UnitMapper.decompose(event)));
		Collections.reverse(snapshots);

		Event loaded = UnitMapper.assembleEvents(snapshots).items().get(0);

		assertEquals(List.of("A1-1", "A1-2", "A2-1", "B1", "AA1"),
				loaded.getPresentations().get(0).getBookings().stream().map(Booking::getLastName).toList());
	}

	@Test
	void eventsAreSortedByName() {
		Event beta = new Event("11111111-1111-1111-1111-111111111112");
		beta.setName("beta");
		Event alpha = new Event("11111111-1111-1111-1111-111111111111");
		alpha.setName("Alpha");
		List<UnitSnapshot> snapshots = new ArrayList<>(snapshots(UnitMapper.decompose(beta)));
		snapshots.addAll(snapshots(UnitMapper.decompose(alpha)));

		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);

		assertEquals(List.of("Alpha", "beta"), assembly.items().stream().map(Event::getName).toList());
	}

	@Test
	void deletedPresentationHidesItsBookings() {
		Event event = simpleEvent();
		Presentation first = event.getPresentations().get(0);
		List<UnitSnapshot> snapshots = snapshots(UnitMapper.decompose(event));
		UnitKey key = UnitKey.presentation(event.getId(), first.getId());
		snapshots = replace(snapshots, withState(find(snapshots, key), Status.OK, true));

		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);

		assertEquals(List.of(), assembly.warnings());
		Event loaded = assembly.items().get(0);
		assertEquals(1, loaded.getPresentations().size());
		assertEquals("Samstag", loaded.getPresentations().get(0).getName());
		assertEquals(1, loaded.getBookings().size());
	}

	@Test
	void deletedEventIsNotAssembled() {
		Event event = simpleEvent();
		List<UnitSnapshot> snapshots = snapshots(UnitMapper.decompose(event));
		snapshots = replace(snapshots, withState(find(snapshots, UnitKey.event(event.getId())), Status.OK, true));

		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);

		assertEquals(List.of(), assembly.items());
		assertEquals(List.of(), assembly.warnings());
	}

	@Test
	void deletedBookingIsNotAssembled() {
		Event event = simpleEvent();
		List<UnitSnapshot> snapshots = snapshots(UnitMapper.decompose(event));
		Presentation first = event.getPresentations().get(0);
		UnitKey key = UnitKey.booking(event.getId(), first.getId(), "seat-a1");
		snapshots = replace(snapshots, withState(find(snapshots, key), Status.OK, true));

		Event loaded = UnitMapper.assembleEvents(snapshots).items().get(0);

		assertEquals(List.of("Meier"), loaded.getPresentations().get(0).getBookings().stream().map(Booking::getLastName).toList());
	}

	@Test
	void headWithoutReadableVersionSkipsEventWithWarning() {
		Event broken = simpleEvent();
		Event fine = realisticEvent();
		List<UnitSnapshot> snapshots = new ArrayList<>(snapshots(UnitMapper.decompose(broken)));
		snapshots = replace(snapshots, noVersion(UnitKey.event(broken.getId()), Status.LOCKED_DAMAGED));
		snapshots.addAll(snapshots(UnitMapper.decompose(fine)));

		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);

		assertEquals(List.of(fine.getId()), assembly.items().stream().map(Event::getId).toList());
		assertEquals(List.of("Das Event " + broken.getId() + " konnte nicht gelesen werden und fehlt."),
				assembly.warnings());
	}

	@Test
	void folderWithoutFilesIsIgnoredSilently() {
		Event event = simpleEvent();
		Presentation first = event.getPresentations().get(0);
		Presentation second = event.getPresentations().get(1);

		// Vorstellung ohne eigene Dateien: ihre Buchungen entfallen mit, ohne Warnung
		List<UnitSnapshot> snapshots = snapshots(UnitMapper.decompose(event));
		snapshots = replace(snapshots, noVersion(UnitKey.presentation(event.getId(), first.getId()), Status.OK));
		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);
		assertEquals(List.of(), assembly.warnings());
		assertEquals(List.of(second.getId()),
				assembly.items().get(0).getPresentations().stream().map(Presentation::getId).toList());
		assertEquals(1, assembly.items().get(0).getBookings().size());

		// Kopf ohne eigene Dateien (Elternordner): kein Event, keine Warnung
		snapshots = replace(snapshots(UnitMapper.decompose(event)), noVersion(UnitKey.event(event.getId()), Status.OK));
		assembly = UnitMapper.assembleEvents(snapshots);
		assertEquals(List.of(), assembly.items());
		assertEquals(List.of(), assembly.warnings());

		// Buchung ohne eigene Dateien
		snapshots = replace(snapshots(UnitMapper.decompose(event)),
				noVersion(UnitKey.booking(event.getId(), first.getId(), "seat-a1"), Status.OK));
		assembly = UnitMapper.assembleEvents(snapshots);
		assertEquals(List.of(), assembly.warnings());
		assertEquals(2, assembly.items().get(0).getBookings().size());
	}

	@Test
	void folderWithoutFilesIsIgnoredSilentlyForHalls() {
		Hall hall = realisticHall();

		Assembly<Hall> assembly = UnitMapper.assembleHalls(List.of(noVersion(UnitKey.hall(hall.getId()), Status.OK)));

		assertEquals(List.of(), assembly.items());
		assertEquals(List.of(), assembly.warnings());
	}

	@Test
	void unitWithoutVersionAndBrokenStatusGivesWarning() {
		Event event = simpleEvent();
		Presentation first = event.getPresentations().get(0);
		List<UnitSnapshot> snapshots = snapshots(UnitMapper.decompose(event));
		snapshots = replace(snapshots, noVersion(UnitKey.presentation(event.getId(), first.getId()), Status.LOCKED_FORMAT));
		snapshots = replace(snapshots, noVersion(UnitKey.booking(event.getId(),
				event.getPresentations().get(1).getId(), "seat-a1x"), Status.LOCKED_DAMAGED));

		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);

		assertEquals(List.of(
				"Die Vorstellung " + first.getId() + " von \"Prunksitzung\" konnte nicht gelesen werden und fehlt.",
				"Eine Buchung von \"Samstag\" konnte nicht gelesen werden und fehlt."), assembly.warnings());
		Event loaded = assembly.items().get(0);
		assertEquals(1, loaded.getPresentations().size());
		assertEquals(0, loaded.getBookings().size());
	}

	@Test
	void contentJacksonCannotReadGivesTheSameWarnings() {
		Event event = simpleEvent();
		Presentation first = event.getPresentations().get(0);
		Presentation second = event.getPresentations().get(1);
		List<UnitSnapshot> snapshots = snapshots(UnitMapper.decompose(event));
		UnitKey presentationKey = UnitKey.presentation(event.getId(), first.getId());
		UnitKey bookingKey = UnitKey.booking(event.getId(), second.getId(), "seat-a1x");
		snapshots = replace(snapshots, garbled(find(snapshots, presentationKey), "bookings"));
		snapshots = replace(snapshots, garbled(find(snapshots, bookingKey), "price"));

		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);

		assertEquals(List.of(
				"Die Vorstellung " + first.getId() + " von \"Prunksitzung\" konnte nicht gelesen werden und fehlt.",
				"Eine Buchung von \"Samstag\" konnte nicht gelesen werden und fehlt."), assembly.warnings());
		assertEquals(List.of(second.getId()),
				assembly.items().get(0).getPresentations().stream().map(Presentation::getId).toList());

		// Kopf, den Jackson nicht liest
		snapshots = replace(snapshots, garbled(find(snapshots, UnitKey.event(event.getId())), "presentations"));
		assembly = UnitMapper.assembleEvents(snapshots);
		assertEquals(List.of(), assembly.items());
		assertEquals("Das Event " + event.getId() + " konnte nicht gelesen werden und fehlt.", assembly.warnings().get(0));
	}

	/** Setzt ein Feld auf einen Wert der falschen Art, den Jackson nicht lesen kann. */
	private static UnitSnapshot garbled(UnitSnapshot snapshot, String field) {
		ObjectNode payload = snapshot.payload().deepCopy();
		payload.set(field, MAPPER.createObjectNode().put("kaputt", true));
		return new UnitSnapshot(snapshot.unit(), snapshot.version(), snapshot.status(), snapshot.deleted(), payload);
	}

	@Test
	void lockedUnitLoadsWithWarning() {
		Event event = simpleEvent();
		Presentation first = event.getPresentations().get(0);
		List<UnitSnapshot> snapshots = snapshots(UnitMapper.decompose(event));
		UnitKey damaged = UnitKey.presentation(event.getId(), first.getId());
		UnitKey newer = UnitKey.booking(event.getId(), first.getId(), "seat-a1");
		snapshots = replace(snapshots, withState(find(snapshots, damaged), Status.LOCKED_DAMAGED, false));
		snapshots = replace(snapshots, withState(find(snapshots, newer), Status.LOCKED_FORMAT, false));

		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);

		assertEquals(1, assembly.items().size());
		assertEquals(3, assembly.items().get(0).getBookings().size());
		assertEquals(2, assembly.warnings().size());
		assertTrue(assembly.warnings().contains(StoreMessages.damaged(damaged.relativePath())));
		assertTrue(assembly.warnings().contains(StoreMessages.newerFormat(newer.relativePath())));
	}

	@Test
	void waitingUnitLoadsWithoutWarning() {
		Event event = simpleEvent();
		List<UnitSnapshot> snapshots = snapshots(UnitMapper.decompose(event));
		snapshots = replace(snapshots, withState(find(snapshots, UnitKey.event(event.getId())), Status.WAITING, false));

		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);

		assertEquals(1, assembly.items().size());
		assertEquals(List.of(), assembly.warnings());
	}

	@Test
	void unitsWithoutParentAreSkippedSilently() {
		Event event = simpleEvent();
		Presentation first = event.getPresentations().get(0);
		List<UnitSnapshot> all = snapshots(UnitMapper.decompose(event));

		// Vorstellung ohne Kopf, Buchung ohne Vorstellung
		Assembly<Event> noHead = UnitMapper.assembleEvents(without(all, UnitKey.event(event.getId())));
		assertEquals(List.of(), noHead.items());
		assertEquals(List.of(), noHead.warnings());

		Assembly<Event> noPresentation = UnitMapper
				.assembleEvents(without(all, UnitKey.presentation(event.getId(), first.getId())));
		assertEquals(List.of(), noPresentation.warnings());
		Event loaded = noPresentation.items().get(0);
		assertEquals(1, loaded.getPresentations().size());
		assertEquals(1, loaded.getBookings().size());
	}

	@Test
	void copiedEventFolderIsDropped() {
		Event event = simpleEvent();
		Presentation first = event.getPresentations().get(0);
		List<UnitSnapshot> original = snapshots(UnitMapper.decompose(event));
		String copyId = event.getId() + "-PCNAME";
		List<UnitSnapshot> snapshots = new ArrayList<>(original);
		// Kopie des Ordners (Hand-Kopie, Konfliktkopie): gleicher Inhalt mit derselben ID, samt Kindern
		for (UnitSnapshot snapshot : original) {
			UnitKey unit = snapshot.unit();
			UnitKey copy = switch (unit.type()) {
			case EVENT -> UnitKey.event(copyId);
			case PRESENTATION -> UnitKey.presentation(copyId, unit.presentationId());
			default -> unit.bookingDir().startsWith("_")
					? UnitKey.unassignedBooking(copyId, unit.presentationId(), unit.bookingDir().substring(1))
					: UnitKey.booking(copyId, unit.presentationId(), unit.bookingDir());
			};
			snapshots.add(new UnitSnapshot(copy, snapshot.version(), snapshot.status(), false, snapshot.payload()));
		}

		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);

		assertEquals(List.of(event.getId()), assembly.items().stream().map(Event::getId).toList());
		assertEquals(2, assembly.items().get(0).getPresentations().size());
		assertEquals(3, assembly.items().get(0).getBookings().size());
		assertEquals(List.of("Das Event " + copyId + " konnte nicht gelesen werden und fehlt."), assembly.warnings());
		assertEquals(first.getId(), assembly.items().get(0).getPresentations().get(0).getId());
	}

	@Test
	void presentationFolderWithOtherIdIsDroppedWithItsBookings() {
		Event event = simpleEvent();
		Presentation first = event.getPresentations().get(0);
		Presentation second = event.getPresentations().get(1);
		List<UnitSnapshot> snapshots = snapshots(UnitMapper.decompose(event));
		UnitSnapshot snapshot = find(snapshots, UnitKey.presentation(event.getId(), first.getId()));
		// der Inhalt von "second" liegt im Ordner von "first"
		UnitSnapshot other = find(snapshots, UnitKey.presentation(event.getId(), second.getId()));
		snapshots = replace(snapshots, new UnitSnapshot(snapshot.unit(), VERSION, Status.OK, false, other.payload()));

		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);

		assertEquals(List.of(second.getId()),
				assembly.items().get(0).getPresentations().stream().map(Presentation::getId).toList());
		assertEquals(1, assembly.items().get(0).getBookings().size());
		assertEquals(List.of(
				"Die Vorstellung " + first.getId() + " von \"Prunksitzung\" konnte nicht gelesen werden und fehlt."),
				assembly.warnings());
	}

	@Test
	void seatFolderWithOtherSeatIdIsDropped() {
		Event event = simpleEvent();
		Presentation first = event.getPresentations().get(0);
		List<UnitSnapshot> snapshots = snapshots(UnitMapper.decompose(event));
		UnitKey folder = UnitKey.booking(event.getId(), first.getId(), "seat-a1");
		ObjectNode payload = find(snapshots, folder).payload().deepCopy();
		payload.put("seatId", "seat-a3");
		snapshots = replace(snapshots, new UnitSnapshot(folder, VERSION, Status.OK, false, payload));

		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);

		assertEquals(List.of("Meier"),
				assembly.items().get(0).getPresentations().get(0).getBookings().stream().map(Booking::getLastName).toList());
		assertEquals(List.of("Eine Buchung von \"Freitag\" konnte nicht gelesen werden und fehlt."), assembly.warnings());
	}

	@Test
	void unassignedFolderWithOtherIdIsDropped() {
		Event event = realisticEvent();
		Presentation second = event.getPresentations().get(1);
		Booking lost = second.getBookings().get(0);
		List<UnitSnapshot> snapshots = snapshots(UnitMapper.decompose(event));
		UnitKey folder = UnitKey.unassignedBooking(event.getId(), second.getId(), lost.getId());
		ObjectNode payload = find(snapshots, folder).payload().deepCopy();
		payload.put("id", "x");
		snapshots = replace(snapshots, new UnitSnapshot(folder, VERSION, Status.OK, false, payload));
		// ein Ordner "_x" mit dem Inhalt einer Buchung auf einem Sitz
		UnitKey seated = UnitKey.booking(event.getId(), event.getPresentations().get(0).getId(), "seat-1");
		snapshots = new ArrayList<>(snapshots);
		snapshots.add(new UnitSnapshot(UnitKey.unassignedBooking(event.getId(), second.getId(), "x"), VERSION, Status.OK,
				false, find(snapshots, seated).payload()));

		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);

		Event loaded = assembly.items().get(0);
		assertEquals(0, loaded.getPresentations().get(1).getBookings().size());
		assertEquals(2, loaded.getPresentations().get(0).getBookings().size());
		assertEquals(2, assembly.warnings().size());
		assertTrue(assembly.warnings().stream().allMatch(w -> w.equals("Eine Buchung von \"Samstag\" konnte nicht gelesen werden und fehlt.")));
	}

	@Test
	void bookingWithoutSeatAndNotMarkedUnassignedSurvivesReload() {
		Event event = new Event("11111111-1111-1111-1111-111111111111");
		Presentation presentation = event.addPresentation("Freitag");
		Booking withoutSeat = booking(presentation, null, "", 0, 0, "Meier");
		assertFalse(withoutSeat.isUnassigned());

		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots(UnitMapper.decompose(event)));

		assertEquals(List.of(), assembly.warnings());
		List<Booking> loaded = assembly.items().get(0).getPresentations().get(0).getBookings();
		assertEquals(List.of(withoutSeat.getId()), loaded.stream().map(Booking::getId).toList());
	}

	@Test
	void hallFolderWithOtherIdIsDropped() {
		Hall hall = realisticHall();
		UnitPayload part = UnitMapper.decompose(hall);
		UnitKey copy = UnitKey.hall(hall.getId() + "-PCNAME");

		Assembly<Hall> assembly = UnitMapper.assembleHalls(
				List.of(snapshot(part), new UnitSnapshot(copy, VERSION, Status.OK, false, part.payload())));

		assertEquals(List.of(hall.getId()), assembly.items().stream().map(Hall::getId).toList());
		assertEquals(List.of("Die Halle " + hall.getId() + "-PCNAME konnte nicht gelesen werden und fehlt."),
				assembly.warnings());
	}

	@Test
	void duplicatePresentationIdIsRejected() {
		Event event = new Event("11111111-1111-1111-1111-111111111111");
		event.setName("Prunksitzung");
		presentation(event, "same", "Freitag", null, null);
		presentation(event, "same", "Samstag", null, null);

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> UnitMapper.decompose(event));

		assertEquals("Zwei Vorstellungen haben dieselbe ID (pres-same) im Event \"Prunksitzung\". "
				+ "Es wurde nichts gespeichert.", e.getMessage());
	}

	@Test
	void presentationsWithTheSameIdInDifferentEventsKeepTheirOwnBookings() {
		Event one = new Event("11111111-1111-1111-1111-111111111111");
		one.setName("Eins");
		Event two = new Event("11111111-1111-1111-1111-111111111112");
		two.setName("Zwei");
		booking(presentation(one, "same", "Freitag", null, null), "seat-1", "A", 1, 1, "Aus Eins");
		booking(presentation(two, "same", "Freitag", null, null), "seat-1", "A", 1, 1, "Aus Zwei");
		booking(two.getPresentations().get(0), "seat-2", "A", 1, 2, "Auch Zwei");
		List<UnitSnapshot> snapshots = new ArrayList<>(snapshots(UnitMapper.decompose(one)));
		snapshots.addAll(snapshots(UnitMapper.decompose(two)));

		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);

		assertEquals(List.of(), assembly.warnings());
		List<Event> loaded = assembly.items();
		assertEquals(List.of("Eins", "Zwei"), loaded.stream().map(Event::getName).toList());
		assertEquals(List.of("Aus Eins"),
				loaded.get(0).getPresentations().get(0).getBookings().stream().map(Booking::getLastName).toList());
		assertEquals(List.of("Aus Zwei", "Auch Zwei"),
				loaded.get(1).getPresentations().get(0).getBookings().stream().map(Booking::getLastName).toList());
	}

	@Test
	void hallRoundTrip() {
		Hall zoo = new Hall("66666666-6666-6666-6666-666666666667");
		zoo.setName("zoo");
		Hall hall = realisticHall();
		List<UnitSnapshot> snapshots = List.of(snapshot(UnitMapper.decompose(zoo)), snapshot(UnitMapper.decompose(hall)));

		Assembly<Hall> assembly = UnitMapper.assembleHalls(snapshots);

		assertEquals(List.of(), assembly.warnings());
		assertEquals(List.of("Stadthalle", "zoo"), assembly.items().stream().map(Hall::getName).toList());
		assertEquals(UnitMapper.normalize(hall), UnitMapper.normalize(assembly.items().get(0)));
		assertSame(assembly.items().get(0), assembly.items().get(0).getSeatingPlans().get(0).getParent());
	}

	@Test
	void deletedHallIsNotAssembled() {
		Hall hall = realisticHall();
		UnitSnapshot deleted = withState(snapshot(UnitMapper.decompose(hall)), Status.OK, true);

		Assembly<Hall> assembly = UnitMapper.assembleHalls(List.of(deleted));

		assertEquals(List.of(), assembly.items());
		assertEquals(List.of(), assembly.warnings());
	}

	@Test
	void hallWithoutReadableVersionGivesWarning() {
		Hall hall = realisticHall();
		Hall fine = new Hall("66666666-6666-6666-6666-666666666667");
		fine.setName("Gut");
		List<UnitSnapshot> snapshots = List.of(noVersion(UnitKey.hall(hall.getId()), Status.LOCKED_DAMAGED),
				snapshot(UnitMapper.decompose(fine)));

		Assembly<Hall> assembly = UnitMapper.assembleHalls(snapshots);

		assertEquals(List.of("Gut"), assembly.items().stream().map(Hall::getName).toList());
		assertEquals(List.of("Die Halle " + hall.getId() + " konnte nicht gelesen werden und fehlt."),
				assembly.warnings());
	}

	@Test
	void lockedHallLoadsWithWarning() {
		Hall hall = realisticHall();
		UnitSnapshot locked = withState(snapshot(UnitMapper.decompose(hall)), Status.LOCKED_FORMAT, false);

		Assembly<Hall> assembly = UnitMapper.assembleHalls(List.of(locked));

		assertEquals(1, assembly.items().size());
		assertEquals(List.of(StoreMessages.newerFormat("halls/" + hall.getId())), assembly.warnings());
	}

	@Test
	void unitsOfOtherTypesAreIgnored() {
		Hall hall = realisticHall();
		Event event = simpleEvent();
		List<UnitSnapshot> all = new ArrayList<>(snapshots(UnitMapper.decompose(event)));
		all.add(snapshot(UnitMapper.decompose(hall)));

		assertEquals(1, UnitMapper.assembleEvents(all).items().size());
		assertEquals(1, UnitMapper.assembleHalls(all).items().size());
		assertEquals(event.getId(), UnitMapper.assembleEvents(all).items().get(0).getId());
		assertEquals(hall.getId(), UnitMapper.assembleHalls(all).items().get(0).getId());
	}

	// <-- assemble
}
