package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;

class TicketSelectionTest {

	private final Event event = new Event();
	private final Presentation presentation = event.addPresentation("Freitag");
	private final TestBookings bookings = new TestBookings();
	private final TicketSelection selection = new TicketSelection();

	private final Booking a1 = booking(1);
	private final Booking a2 = booking(2);
	private final Booking b1 = booking(3);
	private final Booking b2 = booking(4);

	private Booking booking(int seat) {
		return bookings.booking(presentation, 1, seat);
	}

	private TicketNode leaf(Booking booking) {
		return new TicketNode(new TicketRow.BookingRow(new BookingDTO(event, presentation, booking)), List.of());
	}

	private static TicketNode group(String key, TicketNode... children) {
		return new TicketNode(new TicketRow.GroupRow(List.of(TicketColumn.CATEGORY), key, children.length, List.of(key)),
				List.of(children));
	}

	/** Zwei Gruppen „A“ (a1, a2) und „B“ (b1, b2). */
	private List<TicketNode> tree() {
		return List.of(group("A", leaf(a1), leaf(a2)), group("B", leaf(b1), leaf(b2)));
	}

	@Test
	void klickAufGruppeWaehltAlle() {
		List<TicketNode> tree = tree();
		selection.select(tree.get(1).children().get(0));
		selection.select(tree.get(0));
		assertEquals(List.of(a1, a2), selection.selected(tree));
		assertTrue(selection.fullySelected(tree.get(0)));
		assertFalse(selection.fullySelected(tree.get(1)));
	}

	@Test
	void strgKlickGanzMarkierteGruppeNimmtAlleHeraus() {
		List<TicketNode> tree = tree();
		selection.select(tree.get(0));
		selection.add(tree.get(1).children().get(0));
		selection.toggle(tree.get(0));
		assertEquals(List.of(b1), selection.selected(tree));
	}

	@Test
	void strgKlickTeilweiseGruppeNimmtAlleHinein() {
		List<TicketNode> tree = tree();
		selection.select(tree.get(0).children().get(0));
		selection.toggle(tree.get(1).children().get(1));
		selection.toggle(tree.get(0));
		assertEquals(List.of(a1, a2, b2), selection.selected(tree));
		selection.toggle(tree.get(1).children().get(1));
		assertEquals(List.of(a1, a2), selection.selected(tree));
	}

	@Test
	void strgAbwahlInGanzGewaehlterGruppeEntmarkiertKopf() {
		List<TicketNode> tree = tree();
		selection.select(tree.get(0));
		selection.toggle(tree.get(0).children().get(1));
		assertEquals(List.of(a1), selection.selected(tree));
		assertFalse(selection.fullySelected(tree.get(0)));
	}

	@Test
	void leertasteAufGruppeNimmtAlleHinein() {
		List<TicketNode> tree = tree();
		selection.select(tree.get(0).children().get(0));
		selection.add(tree.get(1));
		selection.add(tree.get(1));
		assertEquals(List.of(a1, b1, b2), selection.selected(tree));
	}

	@Test
	void bereichZaehltNurBuchungszeilen() {
		List<TicketNode> tree = tree();
		selection.select(tree.get(1).children().get(1));
		// sichtbar zwischen Anker und Ziel: a2, Kopf „B“ (zugeklappt) – der Kopf trägt nichts bei
		selection.range(List.of(tree.get(0).children().get(1), tree.get(1)), false);
		assertEquals(List.of(a2), selection.selected(tree));
		selection.range(List.of(tree.get(0), tree.get(0).children().get(0)), true);
		assertEquals(List.of(a1, a2), selection.selected(tree));
		assertTrue(selection.fullySelected(tree.get(0)));
	}

	@Test
	void strgAWaehltAuchZugeklappte() {
		List<TicketNode> tree = tree();
		selection.selectAll(tree);
		assertEquals(List.of(a1, a2, b1, b2), selection.selected(tree));
		assertEquals(Set.of(), selection.pathsToExpand(tree, Set.of(List.of("A"), List.of("B"))));
	}

	@Test
	void zuklappenGanzGewaehltBehaeltAuswahl() {
		List<TicketNode> tree = tree();
		selection.select(tree.get(0));
		selection.add(tree.get(1).children().get(0));
		selection.collapsing(tree.get(0));
		assertEquals(List.of(a1, a2, b1), selection.selected(tree));
		assertTrue(selection.fullySelected(tree.get(0)));
	}

	@Test
	void zuklappenTeilweiseNimmtGewaehlteHeraus() {
		List<TicketNode> tree = tree();
		selection.select(tree.get(0).children().get(0));
		selection.add(tree.get(1).children().get(0));
		selection.collapsing(tree.get(1));
		assertEquals(List.of(a1), selection.selected(tree));
		assertEquals(Set.of(), selection.pathsToExpand(tree, Set.of(List.of("B"))));
	}

	@Test
	void gruppeWaechstNichtDurchFremdeBuchung() {
		selection.select(tree().get(0));
		Set<List<Object>> collapsed = Set.of(List.of("A"));
		Booking a3 = booking(5);
		List<TicketNode> rebuilt = List.of(group("A", leaf(a1), leaf(a2), leaf(a3)), group("B", leaf(b1), leaf(b2)));
		selection.rebuilt(rebuilt);
		assertEquals(List.of(a1, a2), selection.selected(rebuilt));
		assertFalse(selection.fullySelected(rebuilt.get(0)));
		assertEquals(Set.of(List.of("A")), selection.pathsToExpand(rebuilt, collapsed));
	}

	@Test
	void neuaufbauEntferntVerschwundene() {
		List<TicketNode> tree = tree();
		selection.selectAll(tree);
		List<TicketNode> rebuilt = List.of(group("A", leaf(a1)), group("B", leaf(b2)));
		selection.rebuilt(rebuilt);
		assertEquals(List.of(a1, b2), selection.selected(rebuilt));
		assertTrue(selection.fullySelected(rebuilt.get(0)));
		// a2 kommt zurück, ist aber nicht mehr gewählt
		selection.rebuilt(tree);
		assertEquals(List.of(a1, b2), selection.selected(tree));
	}

	@Test
	void deselectAusZugeklappterGruppe() {
		List<TicketNode> tree = tree();
		selection.select(tree.get(0));
		selection.add(tree.get(1));
		selection.remove(List.of(a2, b1, b2));
		assertEquals(List.of(a1), selection.selected(tree));
		assertEquals(Set.of(List.of("A")), selection.pathsToExpand(tree, Set.of(List.of("A"), List.of("B"))));
	}

	@Test
	void verschachtelteZugeklappteGruppenWerdenAufgeklappt() {
		TicketNode inner = new TicketNode(
				new TicketRow.GroupRow(List.of(TicketColumn.PRESENTATION), "Freitag", 2,List.of("A", "Freitag")),
				List.of(leaf(a1), leaf(a2)));
		List<TicketNode> tree = List.of(group("A", inner), group("B", leaf(b1)));
		selection.select(inner);
		selection.remove(List.of(a2));
		assertEquals(Set.of(List.of("A"), List.of("A", "Freitag")),
				selection.pathsToExpand(tree, Set.of(List.of("A"), List.of("A", "Freitag"), List.of("B"))));
	}

	@Test
	void restoreNimmtNurBuchungenImBaum() {
		List<TicketNode> tree = tree();
		selection.select(tree.get(0));
		Booking fremd = booking(6);
		selection.set(List.of(b2, fremd), tree);
		assertEquals(List.of(b2), selection.selected(tree));
		List<TicketNode> withFremd = new ArrayList<>(tree);
		withFremd.add(leaf(fremd));
		assertEquals(List.of(b2), selection.selected(withFremd));
	}

	@Test
	void selectedInTabellenreihenfolge() {
		List<TicketNode> tree = tree();
		selection.toggle(tree.get(1).children().get(1));
		selection.toggle(tree.get(0).children().get(0));
		selection.toggle(tree.get(1).children().get(0));
		assertEquals(List.of(a1, b1, b2), selection.selected(tree));
		List<TicketNode> reversed = List.of(tree.get(1), tree.get(0));
		assertEquals(List.of(b1, b2, a1), selection.selected(reversed));
	}

	@Test
	void leerenUndLeereGruppe() {
		List<TicketNode> tree = tree();
		selection.selectAll(tree);
		selection.clear();
		assertEquals(List.of(), selection.selected(tree));
		assertFalse(selection.fullySelected(group("C")));
	}
}
