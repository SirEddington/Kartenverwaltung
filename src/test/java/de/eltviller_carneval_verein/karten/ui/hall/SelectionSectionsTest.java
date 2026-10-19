package de.eltviller_carneval_verein.karten.ui.hall;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.ui.hall.SelectionSections.Level;
import de.eltviller_carneval_verein.karten.ui.hall.SelectionSections.Section;

/** Welche Abschnitte Reihe/Tisch/Sitz das Panel zeigt, mit welchem Titel und ob aufgeklappt, ohne JavaFX. */
class SelectionSectionsTest {

	private final SeatingPlan plan = new Hall().addSeatingPlan();
	private final TableRow rowA = row("A");
	private final Table tableA1 = plan.addTable(rowA);
	private final Seat seat1 = tableA1.addSeat();
	private final Seat seat2 = tableA1.addSeat();
	private final SelectionSections sections = new SelectionSections();

	private TableRow row(String label) {
		TableRow row = plan.addRow();
		row.setLabel(label);
		return row;
	}

	@Test
	void nothingSelectedShowsNoSection() {
		assertEquals(List.of(), sections.sections(List.of(), null, null));
	}

	@Test
	void rowShowsOnlyTheExpandedRowSection() {
		assertEquals(List.of(new Section(Level.ROW, "Reihe A", true)), sections.sections(List.of(rowA), null, null));
	}

	@Test
	void rowWithoutLabel() {
		assertEquals(List.of(new Section(Level.ROW, "Reihe ohne Buchstaben", true)), sections.sections(List.of(row(" ")), null, null));
	}

	@Test
	void severalRowsShareOneSection() {
		assertEquals(List.of(new Section(Level.ROW, "2 Reihen", true)), sections.sections(List.of(rowA, row("B")), null, null));
	}

	@Test
	void tableShowsRowCollapsedAndTableExpanded() {
		assertEquals(List.of(new Section(Level.ROW, "Reihe A", false), new Section(Level.TABLE, "Tisch A1", true)),
				sections.sections(List.of(rowA), tableA1, null));
	}

	@Test
	void seatShowsTheWholeChainWithOnlyTheSeatExpanded() {
		assertEquals(List.of(new Section(Level.ROW, "Reihe A", false), new Section(Level.TABLE, "Tisch A1", false), new Section(Level.SEAT, "Sitz 2", true)),
				sections.sections(List.of(rowA), tableA1, seat2));
	}

	@Test
	void tableWithoutValidRowAndWithoutLetter() {
		TableRow unlabeled = row("");
		plan.addTable(unlabeled);
		plan.addTable(unlabeled);
		Table third = plan.addTable(unlabeled);

		assertEquals(List.of(new Section(Level.TABLE, "Tisch 3", true)), sections.sections(List.of(), third, null));
	}

	@Test
	void seatWithoutTableIsIgnored() {
		assertEquals(List.of(new Section(Level.ROW, "Reihe A", true)), sections.sections(List.of(rowA), null, seat1));
	}

	@Test
	void userChoiceOfALevelIsKeptForOtherSelections() {
		sections.toggled(Level.TABLE, true);
		sections.toggled(Level.SEAT, false);

		assertEquals(List.of(new Section(Level.ROW, "Reihe A", false), new Section(Level.TABLE, "Tisch A1", true), new Section(Level.SEAT, "Sitz 1", false)),
				sections.sections(List.of(rowA), tableA1, seat1));
		assertEquals(List.of(new Section(Level.ROW, "Reihe A", false), new Section(Level.TABLE, "Tisch A1", true), new Section(Level.SEAT, "Sitz 2", false)),
				sections.sections(List.of(rowA), tableA1, seat2));
		// Die Reihe hat der Nutzer nicht angefasst: markiert ist sie aufgeklappt
		assertEquals(List.of(new Section(Level.ROW, "Reihe A", true)), sections.sections(List.of(rowA), null, null));
	}

	@Test
	void userChoiceAlsoAppliesToTheSelectedLevel() {
		sections.toggled(Level.ROW, false);
		assertEquals(List.of(new Section(Level.ROW, "Reihe A", false)), sections.sections(List.of(rowA), null, null));

		sections.toggled(Level.ROW, true);
		assertEquals(List.of(new Section(Level.ROW, "Reihe A", true), new Section(Level.TABLE, "Tisch A1", true)),
				sections.sections(List.of(rowA), tableA1, null));
	}

	@Test
	void rowNames() {
		assertEquals("Reihe A", SelectionSections.rowName(rowA));
		assertEquals("Reihe ohne Buchstaben", SelectionSections.rowName(row(null)));
	}
}
