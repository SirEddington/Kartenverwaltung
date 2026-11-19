package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.ui.sales.TicketFilter.FilterValue;

class TicketFilterTest {

	private final Event event = new Event();
	private final Presentation first = event.addPresentation("Freitag");
	private final Presentation second = event.addPresentation("Samstag");
	private final TestBookings bookings = new TestBookings();
	private final TicketFilter filter = new TicketFilter();

	private BookingDTO row(Presentation presentation, int seat, String lastName) {
		BookingDTO row = new BookingDTO(event, presentation, bookings.booking(presentation, 1, seat));
		row.getBooking().setLastName(lastName);
		return row;
	}

	private List<Presentation> order() {
		return event.getPresentations();
	}

	@Test
	void abgewaehlterSchluesselFiltert() {
		BookingDTO a = row(first, 1, "Abel");
		BookingDTO b = row(first, 2, "Bode");
		filter.setDeselected(TicketColumn.LAST_NAME, Set.of(TicketColumn.LAST_NAME.keyOf(a)));
		assertFalse(filter.passes(a));
		assertTrue(filter.passes(b));
		assertTrue(filter.isActive(TicketColumn.LAST_NAME));
		assertFalse(filter.isActive(TicketColumn.SEAT));
	}

	@Test
	void werteListeUnterAnderenFilternMitAnzahl() {
		BookingDTO a = row(first, 1, "Abel");
		BookingDTO b = row(second, 2, "Abel");
		BookingDTO c = row(second, 3, "Bode");
		List<BookingDTO> all = List.of(a, b, c);
		filter.setDeselected(TicketColumn.PRESENTATION, Set.of(TicketColumn.PRESENTATION.keyOf(a)));

		List<FilterValue> names = filter.values(TicketColumn.LAST_NAME, all, order());
		assertEquals(List.of("Abel", "Bode"), names.stream().map(FilterValue::text).toList());
		assertEquals(List.of(1, 1), names.stream().map(FilterValue::count).toList());

		// der Filter der eigenen Spalte zählt nicht: beide Vorstellungen bleiben in der Liste
		List<FilterValue> presentations = filter.values(TicketColumn.PRESENTATION, all, order());
		assertEquals(List.of("Freitag", "Samstag"), presentations.stream().map(FilterValue::text).toList());
		assertEquals(List.of(1, 2), presentations.stream().map(FilterValue::count).toList());
	}

	@Test
	void neuerWertIstSichtbar() {
		BookingDTO a = row(first, 1, "Abel");
		BookingDTO b = row(first, 2, "Neu");
		filter.setDeselected(TicketColumn.LAST_NAME, Set.of(TicketColumn.LAST_NAME.keyOf(a)));
		assertTrue(filter.passes(b));
	}

	@Test
	void abgewaehlterBleibtWennNichtMehrInListe() {
		BookingDTO a = row(first, 1, "Abel");
		BookingDTO b = row(first, 2, "Bode");
		filter.setDeselected(TicketColumn.LAST_NAME, Set.of(TicketColumn.LAST_NAME.keyOf(a)));
		assertEquals(1, filter.values(TicketColumn.LAST_NAME, List.of(b), order()).size());
		assertEquals(Set.of(TicketColumn.LAST_NAME.keyOf(a)), filter.deselected(TicketColumn.LAST_NAME));
		assertFalse(filter.passes(a));
	}

	@Test
	void clearLeertAlle() {
		BookingDTO a = row(first, 1, "Abel");
		assertFalse(filter.clear());
		filter.setDeselected(TicketColumn.LAST_NAME, Set.of(TicketColumn.LAST_NAME.keyOf(a)));
		filter.setDeselected(TicketColumn.SEAT, Set.of(1));
		assertTrue(filter.clear());
		assertTrue(filter.passes(a));
		assertFalse(filter.isActive(TicketColumn.LAST_NAME));
		assertEquals(Set.of(), filter.deselected(TicketColumn.SEAT));
	}

	@Test
	void setDeselectedMeldetNurEchteAenderung() {
		assertFalse(filter.setDeselected(TicketColumn.SEAT, Set.of()));
		assertTrue(filter.setDeselected(TicketColumn.SEAT, Set.of(1)));
		assertFalse(filter.setDeselected(TicketColumn.SEAT, Set.of(1)));
		assertTrue(filter.setDeselected(TicketColumn.SEAT, Set.of()));
		assertFalse(filter.isActive(TicketColumn.SEAT));
	}

	@Test
	void ausnahmenZaehlenTrotzAndererFilterInDerWerteliste() {
		BookingDTO a = row(first, 1, "Abel");
		BookingDTO b = row(second, 2, "Bode");
		filter.setDeselected(TicketColumn.PRESENTATION, Set.of(TicketColumn.PRESENTATION.keyOf(a)));

		// a ist selbst geändert (Spec Abschnitt 11): Es zählt in der Werteliste, obwohl der Filter der Vorstellung es ausschließt
		List<FilterValue> names = filter.values(TicketColumn.LAST_NAME, List.of(a, b), row -> row == a, order());
		assertEquals(List.of("Abel", "Bode"), names.stream().map(FilterValue::text).toList());
		assertEquals(List.of("Bode"), filter.values(TicketColumn.LAST_NAME, List.of(a, b), order()).stream().map(FilterValue::text).toList());
	}
}
