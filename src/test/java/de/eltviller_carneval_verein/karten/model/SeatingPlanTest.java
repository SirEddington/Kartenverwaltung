package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SeatingPlanTest {

	private static SeatingPlan newPlan() {
		Hall hall = new Hall();
		return hall.addSeatingPlan();
	}

	private static Table addNumberedTable(SeatingPlan plan, TableRow row, int number) {
		Table table = plan.addTable(row);
		table.setTableNumber(number);
		return table;
	}

	@Test
	void nextTableNumber_in_Reihe_mit_Buchstabe_zaehlt_pro_Reihe() {
		SeatingPlan plan = newPlan();
		TableRow a = plan.addRow();
		a.setLabel("A");
		TableRow b = plan.addRow();
		b.setLabel("B");
		addNumberedTable(plan, a, 1);
		addNumberedTable(plan, a, 2);
		addNumberedTable(plan, a, 4);

		assertEquals(5, plan.nextTableNumber(a));
		assertEquals(1, plan.nextTableNumber(b));
	}

	@Test
	void nextTableNumber_ohne_Buchstabe_zaehlt_ueber_alle_Reihen() {
		SeatingPlan plan = newPlan();
		TableRow first = plan.addRow();
		TableRow second = plan.addRow();
		for (int i = 1; i <= 8; i++) {
			addNumberedTable(plan, first, i);
		}
		addNumberedTable(plan, second, 9);
		addNumberedTable(plan, second, 10);

		assertEquals(11, plan.nextTableNumber(first));
		assertEquals(11, plan.nextTableNumber(second));
	}

	@Test
	void addTable_haengt_hinten_an() {
		SeatingPlan plan = newPlan();
		plan.setDefaultTableWidth(120);
		plan.setDefaultTableHeight(80);
		TableRow row = plan.addRow();
		row.setLabel("A");
		addNumberedTable(plan, row, 1);
		addNumberedTable(plan, row, 2);

		Table added = plan.addTable(row);

		assertEquals(3, added.getTableNumber());
		List<Table> tables = plan.getTablesOf(row);
		assertSame(added, tables.get(tables.size() - 1));
		assertSame(plan, added.getPlan());
		assertEquals(row.getId(), added.getRowId());
		assertEquals(120, added.getWidth());
		assertEquals(80, added.getHeight());
	}

	@Test
	void renumber_vergibt_lueckenlos() {
		SeatingPlan plan = newPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		Table t1 = addNumberedTable(plan, row, 1);
		Table t2 = addNumberedTable(plan, row, 2);
		Table t4 = addNumberedTable(plan, row, 4);

		plan.renumber(row);

		assertEquals(1, t1.getTableNumber());
		assertEquals(2, t2.getTableNumber());
		assertEquals(3, t4.getTableNumber());
		assertEquals(List.of(t1, t2, t4), plan.getTablesOf(row));
	}

	@Test
	void renumber_ohne_Buchstabe_nummeriert_ueber_alle_Reihen_in_Reihenfolge() {
		SeatingPlan plan = newPlan();
		TableRow first = plan.addRow();
		TableRow second = plan.addRow();
		Table a = addNumberedTable(plan, second, 7);
		Table b = addNumberedTable(plan, first, 5);
		Table c = addNumberedTable(plan, first, 2);

		plan.renumber(second);

		assertEquals(1, c.getTableNumber());
		assertEquals(2, b.getTableNumber());
		assertEquals(3, a.getTableNumber());
	}

	@Test
	void fillRow_legt_Tische_mit_Sitzen_an() {
		SeatingPlan plan = newPlan();
		plan.setDefaultSeatWidth(40);
		plan.setDefaultSeatHeight(30);
		TableRow row = plan.addRow();

		plan.fillRow(row, 3, 4);

		List<Table> tables = plan.getTablesOf(row);
		assertEquals(3, tables.size());
		for (int i = 0; i < tables.size(); i++) {
			Table table = tables.get(i);
			assertEquals(i + 1, table.getTableNumber());
			assertEquals(4, table.getSeats().size());
			for (int s = 0; s < 4; s++) {
				Seat seat = table.getSeats().get(s);
				assertEquals(s + 1, seat.getSeatNumber());
				assertEquals(40, seat.getWidth());
				assertEquals(30, seat.getHeight());
				assertSame(table, seat.getParent());
			}
		}
		assertEquals(12, plan.getSeats().size());
	}

	@Test
	void displayName() {
		SeatingPlan plan = newPlan();
		TableRow lettered = plan.addRow();
		lettered.setLabel("A");
		TableRow plain = plan.addRow();
		Table withLetter = addNumberedTable(plan, lettered, 3);
		Table withoutLetter = addNumberedTable(plan, plain, 3);

		assertEquals("Tisch A3", withLetter.getDisplayName());
		assertEquals("Tisch 3", withoutLetter.getDisplayName());
		assertEquals("Tisch 5", orphanTable(5).getDisplayName());
	}

	private static Table orphanTable(int number) {
		Table table = new Table();
		table.setTableNumber(number);
		return table;
	}

	@Test
	void deleteRow_loescht_auch_die_Tische_der_Reihe() {
		SeatingPlan plan = newPlan();
		TableRow keep = plan.addRow();
		keep.setLabel("A");
		TableRow remove = plan.addRow();
		remove.setLabel("B");
		Table kept = plan.addTable(keep);
		plan.addTable(remove);
		plan.addTable(remove);

		assertTrue(plan.deleteRow(remove));

		assertEquals(List.of(keep), plan.getRows());
		assertEquals(List.of(kept), plan.getTables());
		assertNull(plan.findRow(remove.getId()));
	}

	@Test
	void findRow_und_findSeat() {
		SeatingPlan plan = newPlan();
		TableRow row = plan.addRow();
		plan.fillRow(row, 1, 2);
		Seat seat = plan.getSeats().get(1);

		assertSame(row, plan.findRow(row.getId()));
		assertNull(plan.findRow("gibt-es-nicht"));
		assertNull(plan.findRow(null));
		assertSame(seat, plan.findSeat(seat.getId()));
		assertNull(plan.findSeat("gibt-es-nicht"));
		assertNull(plan.findSeat(null));
	}

	@Test
	void copy_vergibt_neue_Ids_und_verweist_auf_kopierte_Reihen() {
		SeatingPlan plan = newPlan();
		plan.setName("Original");
		plan.setDescription("Beschreibung");
		plan.setDefaultTableWidth(120);
		plan.setDefaultSeatWidth(40);
		TableRow row = plan.addRow();
		row.setLabel("A");
		row.setAngle(15);
		row.setAisleAfter(3);
		plan.fillRow(row, 2, 3);
		plan.getTables().get(0).setRotation(90);

		SeatingPlan copy = plan.copy();

		assertNotEquals(plan.getId(), copy.getId());
		assertEquals("Original", copy.getName());
		assertEquals("Beschreibung", copy.getDescription());
		assertEquals(120, copy.getDefaultTableWidth());
		assertEquals(40, copy.getDefaultSeatWidth());
		assertEquals(1, copy.getRows().size());
		TableRow copiedRow = copy.getRows().get(0);
		assertNotEquals(row.getId(), copiedRow.getId());
		assertSame(copy, copiedRow.getParent());
		assertEquals("A", copiedRow.getLabel());
		assertEquals(15, copiedRow.getAngle());
		assertEquals(3, copiedRow.getAisleAfter());
		assertEquals(2, copy.getTables().size());
		assertEquals(6, copy.getSeats().size());
		assertEquals(90, copy.getTables().get(0).getRotation());

		Set<String> oldIds = new HashSet<>();
		oldIds.add(plan.getId());
		oldIds.add(row.getId());
		plan.getTables().forEach(t -> oldIds.add(t.getId()));
		plan.getSeats().forEach(s -> oldIds.add(s.getId()));
		for (Table table : copy.getTables()) {
			assertTrue(!oldIds.contains(table.getId()));
			assertSame(copy, table.getPlan());
			assertEquals(copiedRow.getId(), table.getRowId());
			for (Seat seat : table.getSeats()) {
				assertTrue(!oldIds.contains(seat.getId()));
				assertSame(table, seat.getParent());
			}
		}
		// Das Original bleibt unverändert
		assertEquals(row.getId(), plan.getTables().get(0).getRowId());
	}

	@Test
	void addSeatingPlan_nimmt_ersten_freien_Namen_und_setzt_Parent() {
		Hall hall = new Hall();

		SeatingPlan first = hall.addSeatingPlan();
		SeatingPlan second = hall.addSeatingPlan();
		hall.getSeatingPlans().remove(first);
		SeatingPlan third = hall.addSeatingPlan();

		assertEquals("Bestuhlung 1", first.getName());
		assertEquals("Bestuhlung 2", second.getName());
		assertEquals("Bestuhlung 1", third.getName());
		assertSame(hall, third.getParent());
	}

	@Test
	void findSeatingPlan_null_und_unbekannt_liefern_null() {
		Hall hall = new Hall();
		SeatingPlan plan = hall.addSeatingPlan();

		assertSame(plan, hall.findSeatingPlan(plan.getId()));
		assertNull(hall.findSeatingPlan(null));
		assertNull(hall.findSeatingPlan("gibt-es-nicht"));
	}
}
