package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;

class TicketGroupingTest {

	private final Event event = new Event();
	private final Presentation first = event.addPresentation("Freitag");
	private final Presentation second = event.addPresentation("Samstag");
	private final List<Presentation> presentations = List.of(first, second);
	private final TestBookings bookings = new TestBookings();

	private BookingDTO row(Presentation presentation, int table, int seat, String category) {
		BookingDTO dto = new BookingDTO(event, presentation, bookings.booking(presentation, table, seat));
		dto.getBooking().setCategory(category);
		return dto;
	}

	private List<BookingDTO> inBaseOrder(BookingDTO... rows) {
		List<BookingDTO> list = new ArrayList<>(List.of(rows));
		list.sort(TicketColumn.baseOrder(presentations));
		return list;
	}

	private List<TicketNode> group(List<BookingDTO> rows, List<TicketColumn> grouping, GroupOrder order, Comparator<BookingDTO> sort) {
		return TicketGrouping.group(rows, grouping, order, true, sort, presentations);
	}

	private List<TicketNode> flat(List<BookingDTO> rows, List<TicketColumn> grouping, GroupOrder order) {
		return TicketGrouping.group(rows, grouping, order, false, null, presentations);
	}

	private BookingDTO named(int seat, String lastName, String firstName) {
		BookingDTO dto = row(first, 1, seat, "A");
		dto.getBooking().setLastName(lastName);
		dto.getBooking().setFirstName(firstName);
		return dto;
	}

	private static TicketRow.GroupRow groupOf(TicketNode node) {
		return (TicketRow.GroupRow) node.row();
	}

	@Test
	void eineEbeneJeKombination() {
		List<TicketColumn> columns = List.of(TicketColumn.LAST_NAME, TicketColumn.FIRST_NAME);
		List<TicketNode> nodes = flat(inBaseOrder(named(1, "Hulbert", "Sedrick"), named(2, "Hulbert", "Silas"), named(3, "Hulbert", "Sedrick")),
				columns, GroupOrder.VALUE);
		assertEquals(List.of("Hulbert, Sedrick", "Hulbert, Silas"), texts(nodes));
		assertEquals(2, groupOf(nodes.get(0)).count());
		assertEquals(1, groupOf(nodes.get(1)).count());
		assertEquals(columns, groupOf(nodes.get(0)).columns());
		assertEquals(1, groupOf(nodes.get(0)).path().size());
		assertEquals(2, nodes.get(0).children().size());
		assertTrue(nodes.get(0).children().get(0).row() instanceof TicketRow.BookingRow);
		assertEquals("Nachname, Vorname", groupOf(nodes.get(0)).columnsText());
	}

	@Test
	void eineEbeneLeererWertAlsLeer() {
		List<TicketNode> nodes = flat(inBaseOrder(named(1, "Hulbert", null)), List.of(TicketColumn.LAST_NAME, TicketColumn.FIRST_NAME), GroupOrder.VALUE);
		assertEquals(List.of("Hulbert, (leer)"), texts(nodes));
	}

	@Test
	void eineEbeneNachWertSpalteFuerSpalte() {
		List<BookingDTO> rows = inBaseOrder(named(1, "Meier", "Anna"), named(2, "Abel", "Zoe"), named(3, "Abel", "Ben"), named(4, "Meier", null));
		assertEquals(List.of("Abel, Ben", "Abel, Zoe", "Meier, Anna", "Meier, (leer)"),
				texts(flat(rows, List.of(TicketColumn.LAST_NAME, TicketColumn.FIRST_NAME), GroupOrder.VALUE)));
	}

	@Test
	void eineEbeneNachGroesse() {
		List<BookingDTO> rows = inBaseOrder(named(1, "Abel", "Zoe"), named(2, "Meier", "Anna"), named(3, "Meier", "Anna"), named(4, "Abel", "Ben"),
				named(5, "Cord", "Eva"), named(6, "Cord", "Eva"));
		assertEquals(List.of("Cord, Eva", "Meier, Anna", "Abel, Ben", "Abel, Zoe"),
				texts(flat(rows, List.of(TicketColumn.LAST_NAME, TicketColumn.FIRST_NAME), GroupOrder.SIZE)));
	}

	@Test
	void eineSpalteGleichWieVerschachtelt() {
		List<BookingDTO> rows = inBaseOrder(row(first, 1, 1, "A"), row(first, 1, 2, "B"), row(first, 1, 3, "A"));
		List<TicketNode> nested = group(rows, List.of(TicketColumn.CATEGORY), GroupOrder.VALUE, null);
		List<TicketNode> single = flat(rows, List.of(TicketColumn.CATEGORY), GroupOrder.VALUE);
		assertEquals(texts(nested), texts(single));
		for (int i = 0; i < nested.size(); i++) {
			assertEquals(groupOf(nested.get(i)), groupOf(single.get(i)));
			assertEquals(List.of(groupOf(single.get(i)).key()), groupOf(single.get(i)).path());
			assertEquals(nested.get(i).children().size(), single.get(i).children().size());
		}
	}

	private static List<String> texts(List<TicketNode> nodes) {
		return nodes.stream().map(node -> ((TicketRow.GroupRow) node.row()).text()).toList();
	}

	private static List<BookingDTO> dtos(List<TicketNode> nodes) {
		return nodes.stream().map(node -> ((TicketRow.BookingRow) node.row()).dto()).toList();
	}

	@Test
	void ohneGruppierungFlach() {
		List<BookingDTO> rows = inBaseOrder(row(first, 1, 1, "A"), row(first, 1, 2, "B"));
		List<TicketNode> nodes = group(rows, List.of(), GroupOrder.VALUE, null);
		assertEquals(rows, dtos(nodes));
		assertTrue(nodes.stream().allMatch(node -> node.children().isEmpty()));
	}

	@Test
	void eineEbene() {
		BookingDTO a1 = row(first, 1, 1, "A");
		BookingDTO b = row(first, 1, 2, "B");
		BookingDTO a2 = row(first, 1, 3, "A");
		List<TicketNode> nodes = group(inBaseOrder(a1, b, a2), List.of(TicketColumn.CATEGORY), GroupOrder.VALUE, null);
		assertEquals(List.of("A", "B"), texts(nodes));
		assertEquals(List.of(a1, a2), dtos(nodes.get(0).children()));
		TicketRow.GroupRow groupA = (TicketRow.GroupRow) nodes.get(0).row();
		assertEquals(List.of(TicketColumn.CATEGORY), groupA.columns());
		assertEquals(2, groupA.count());
		assertEquals(1, ((TicketRow.GroupRow) nodes.get(1).row()).count());
	}

	@Test
	void zweiEbenenAnzahlUeberEbenen() {
		List<BookingDTO> rows = inBaseOrder(row(first, 1, 1, "A"), row(first, 1, 2, "A"), row(second, 1, 1, "A"));
		List<TicketNode> nodes = group(rows, List.of(TicketColumn.CATEGORY, TicketColumn.PRESENTATION), GroupOrder.VALUE, null);
		assertEquals(1, nodes.size());
		assertEquals(3, ((TicketRow.GroupRow) nodes.get(0).row()).count());
		List<TicketNode> inner = nodes.get(0).children();
		assertEquals(List.of("Freitag", "Samstag"), texts(inner));
		assertEquals(2, ((TicketRow.GroupRow) inner.get(0).row()).count());
		assertEquals(1, ((TicketRow.GroupRow) inner.get(1).row()).count());
		assertEquals(2, inner.get(0).children().size());
	}

	@Test
	void valueAufsteigend() {
		List<BookingDTO> rows = inBaseOrder(row(first, 1, 1, "C"), row(first, 1, 2, "A"), row(first, 1, 3, "B"), row(first, 1, 4, ""));
		assertEquals(List.of("A", "B", "C", "(leer)"), texts(group(rows, List.of(TicketColumn.CATEGORY), GroupOrder.VALUE, null)));
	}

	@Test
	void sizeAbsteigendGleichstandNachWert() {
		List<BookingDTO> rows = inBaseOrder(row(first, 1, 1, "A"), row(first, 1, 2, "B"), row(first, 1, 3, "B"),
				row(first, 1, 4, "C"), row(first, 1, 5, "C"), row(first, 1, 6, "D"), row(first, 1, 7, "D"), row(first, 1, 8, "D"));
		assertEquals(List.of("D", "B", "C", "A"), texts(group(rows, List.of(TicketColumn.CATEGORY), GroupOrder.SIZE, null)));
	}

	@Test
	void sizeGiltAufAllenEbenen() {
		List<BookingDTO> rows = inBaseOrder(row(first, 1, 1, "A"), row(second, 1, 1, "A"), row(second, 1, 2, "A"));
		List<TicketNode> nodes = group(rows, List.of(TicketColumn.CATEGORY, TicketColumn.PRESENTATION), GroupOrder.SIZE, null);
		assertEquals(List.of("Samstag", "Freitag"), texts(nodes.get(0).children()));
	}

	@Test
	void sortierungNurInUntersterEbene() {
		BookingDTO a1 = row(first, 1, 1, "A");
		BookingDTO a2 = row(first, 1, 2, "A");
		BookingDTO b1 = row(first, 1, 3, "B");
		BookingDTO b2 = row(first, 1, 4, "B");
		Comparator<BookingDTO> seatDescending = Comparator.comparingInt((BookingDTO dto) -> dto.getBooking().getSeatNumber()).reversed();
		List<TicketNode> nodes = group(inBaseOrder(a1, a2, b1, b2), List.of(TicketColumn.CATEGORY), GroupOrder.VALUE, seatDescending);
		assertEquals(List.of("A", "B"), texts(nodes));
		assertEquals(List.of(a2, a1), dtos(nodes.get(0).children()));
		assertEquals(List.of(b2, b1), dtos(nodes.get(1).children()));
	}

	@Test
	void ohneSortierungGrundordnung() {
		List<BookingDTO> rows = inBaseOrder(row(first, 1, 1, "A"), row(first, 1, 2, "A"), row(second, 1, 1, "A"));
		assertEquals(rows, dtos(group(rows, List.of(TicketColumn.CATEGORY), GroupOrder.VALUE, null).get(0).children()));
	}

	@Test
	void gleichstandInSortierungNachGrundordnung() {
		List<BookingDTO> rows = inBaseOrder(row(first, 1, 1, "A"), row(first, 1, 2, "A"), row(first, 1, 3, "A"));
		Comparator<BookingDTO> constant = (a, b) -> 0;
		assertEquals(rows, dtos(group(rows, List.of(), GroupOrder.VALUE, constant)));
	}

	@Test
	void pfadeStabilBeiGleichenSchluesseln() {
		List<BookingDTO> rows = inBaseOrder(row(first, 1, 1, "A"), row(second, 1, 1, "A"));
		List<TicketColumn> grouping = List.of(TicketColumn.CATEGORY, TicketColumn.PRESENTATION);
		List<TicketNode> one = group(rows, grouping, GroupOrder.VALUE, null);
		List<TicketNode> two = group(new ArrayList<>(rows), grouping, GroupOrder.VALUE, null);
		TicketRow.GroupRow inner = (TicketRow.GroupRow) one.get(0).children().get(1).row();
		TicketRow.GroupRow again = (TicketRow.GroupRow) two.get(0).children().get(1).row();
		assertEquals(inner.path(), again.path());
		assertEquals(2, inner.path().size());
		assertEquals(List.of(((TicketRow.GroupRow) one.get(0).row()).key()), ((TicketRow.GroupRow) one.get(0).row()).path());
	}

	@Test
	void keineLeerenGruppen() {
		assertTrue(group(List.of(), List.of(TicketColumn.CATEGORY), GroupOrder.VALUE, null).isEmpty());
		assertTrue(group(List.of(), List.of(), GroupOrder.VALUE, null).isEmpty());
	}
}
