package de.eltviller_carneval_verein.karten.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonManagedReference;

/**
 * Eine Bestuhlung einer {@link Hall Halle}: Tischreihen, Tische und Sitze. Die Setter nehmen jeden Wert an; die
 * fachlichen Regeln stehen in den Validatoren.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SeatingPlan {
	private final String id;
	@JsonBackReference("hall-plan")
	private Hall parentHall;

	private String name;
	private String description;

	// Standardmaße für neue Tische und Sitze
	private double defaultTableWidth;
	private double defaultTableHeight;
	private double defaultSeatWidth;
	private double defaultSeatHeight;

	@JsonManagedReference("plan-row")
	private List<TableRow> rows = new ArrayList<>();

	@JsonManagedReference("plan-table")
	private List<Table> tables = new ArrayList<>();

	// Konstuktoren -->
	public SeatingPlan() {
		this.id = UUID.randomUUID().toString();
	}

	public SeatingPlan(String id) {
		this.id = (id != null) ? id : UUID.randomUUID().toString();
	}
	// <-- Konstuktoren

	// Getter und Setter -->
	public String getId() {
		return id;
	}

	@JsonIgnore
	public Hall getParent() {
		return parentHall;
	}

	@JsonIgnore
	public void setParent(Hall parentHall) {
		this.parentHall = parentHall;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public double getDefaultTableWidth() {
		return defaultTableWidth;
	}

	public void setDefaultTableWidth(double defaultTableWidth) {
		this.defaultTableWidth = defaultTableWidth;
	}

	public double getDefaultTableHeight() {
		return defaultTableHeight;
	}

	public void setDefaultTableHeight(double defaultTableHeight) {
		this.defaultTableHeight = defaultTableHeight;
	}

	public double getDefaultSeatWidth() {
		return defaultSeatWidth;
	}

	public void setDefaultSeatWidth(double defaultSeatWidth) {
		this.defaultSeatWidth = defaultSeatWidth;
	}

	public double getDefaultSeatHeight() {
		return defaultSeatHeight;
	}

	public void setDefaultSeatHeight(double defaultSeatHeight) {
		this.defaultSeatHeight = defaultSeatHeight;
	}

	public List<TableRow> getRows() {
		return rows;
	}

	public void setRows(List<TableRow> rows) {
		this.rows = rows;
		if (rows != null) {
			rows.forEach(r -> r.setParent(this));
		}
	}

	public List<Table> getTables() {
		return tables;
	}

	public void setTables(List<Table> tables) {
		this.tables = tables;
		if (tables != null) {
			tables.forEach(t -> t.setPlan(this));
		}
	}

	/** Alle Sitze aller Tische in der Reihenfolge der Tischliste. */
	@JsonIgnore
	public List<Seat> getSeats() {
		List<Seat> seats = new ArrayList<>();
		for (Table table : tables) {
			seats.addAll(table.getSeats());
		}
		return seats;
	}
	// <-- Getter und Setter

	public TableRow addRow() {
		TableRow newRow = new TableRow();
		newRow.setParent(this);
		rows.add(newRow);
		return newRow;
	}

	/** Löscht die Reihe samt ihrer Tische. */
	public boolean deleteRow(TableRow row) {
		if (row == null || !rows.remove(row)) {
			return false;
		}
		tables.removeIf(t -> Objects.equals(t.getRowId(), row.getId()));
		return true;
	}

	public TableRow findRow(String rowId) {
		if (rowId == null) {
			return null;
		}
		for (TableRow row : rows) {
			if (rowId.equals(row.getId())) {
				return row;
			}
		}
		return null;
	}

	public Seat findSeat(String seatId) {
		if (seatId == null) {
			return null;
		}
		for (Seat seat : getSeats()) {
			if (seatId.equals(seat.getId())) {
				return seat;
			}
		}
		return null;
	}

	/** Fügt einen Tisch hinten an die Reihe an; die Nummer ist {@link #nextTableNumber(TableRow)}. */
	public Table addTable(TableRow row) {
		Table newTable = new Table();
		newTable.setPlan(this);
		newTable.setRowId(row.getId());
		newTable.setTableNumber(nextTableNumber(row));
		newTable.setWidth(defaultTableWidth);
		newTable.setHeight(defaultTableHeight);

		tables.add(newTable);
		return newTable;
	}

	/** Löscht den Tisch samt seiner Sitze. */
	public boolean deleteTable(Table table) {
		return table != null && tables.remove(table);
	}

	/** Die Tische der Reihe, nach Nummer sortiert (so werden sie angezeigt). */
	public List<Table> getTablesOf(TableRow row) {
		String rowId = row.getId();
		return tables.stream().filter(t -> Objects.equals(t.getRowId(), rowId))
				.sorted(Comparator.comparingInt(Table::getTableNumber)).toList();
	}

	/**
	 * Höchste Nummer im Geltungsbereich der Reihe + 1, bei leerem Geltungsbereich 1. Eine Reihe mit Buchstaben zählt
	 * für sich, alle Reihen ohne Buchstaben zählen zusammen.
	 */
	public int nextTableNumber(TableRow row) {
		int max = 0;
		for (Table table : tablesInScope(row)) {
			max = Math.max(max, table.getTableNumber());
		}
		return max + 1;
	}

	/**
	 * Vergibt die Nummern im Geltungsbereich der Reihe lückenlos von 1 an, in der Reihenfolge der Reihenliste und
	 * innerhalb einer Reihe nach der bisherigen Nummer.
	 */
	public void renumber(TableRow row) {
		int number = 1;
		for (Table table : tablesInScope(row)) {
			table.setTableNumber(number++);
		}
	}

	/** Legt {@code tableCount} Tische mit je {@code seatsPerTable} Sitzen hinten an die Reihe an. */
	public void fillRow(TableRow row, int tableCount, int seatsPerTable) {
		for (int t = 0; t < tableCount; t++) {
			Table table = addTable(row);
			for (int s = 0; s < seatsPerTable; s++) {
				table.addSeat();
			}
		}
	}

	/**
	 * Tiefe Kopie der Bestuhlung (ohne Halle als Elternteil gesetzt): neue Ids für Bestuhlung, Reihen, Tische und
	 * Sitze, die {@code rowId} der kopierten Tische zeigt auf die kopierten Reihen. Von den Sitzen werden Nummer,
	 * Rollstuhlkennzeichen, Position und Maße übernommen; Buchungen gehören zur Vorstellung und bleiben unberührt.
	 */
	public SeatingPlan copy() {
		SeatingPlan copy = new SeatingPlan();
		copy.setName(name);
		copy.setDescription(description);
		copy.setDefaultTableWidth(defaultTableWidth);
		copy.setDefaultTableHeight(defaultTableHeight);
		copy.setDefaultSeatWidth(defaultSeatWidth);
		copy.setDefaultSeatHeight(defaultSeatHeight);

		Map<String, TableRow> copiedRows = new HashMap<>();
		for (TableRow row : rows) {
			TableRow copiedRow = copy.addRow();
			copiedRow.setLabel(row.getLabel());
			copiedRow.setPosX(row.getPosX());
			copiedRow.setPosY(row.getPosY());
			copiedRow.setAngle(row.getAngle());
			copiedRow.setTableRotation(row.getTableRotation());
			copiedRow.setTableGap(row.getTableGap());
			copiedRow.setAisleWidth(row.getAisleWidth());
			copiedRow.setAisleAfter(row.getAisleAfter());
			copiedRows.put(row.getId(), copiedRow);
		}

		for (Table table : tables) {
			Table copiedTable = new Table();
			copiedTable.setPlan(copy);
			TableRow copiedRow = copiedRows.get(table.getRowId());
			copiedTable.setRowId(copiedRow != null ? copiedRow.getId() : table.getRowId());
			copiedTable.setTableNumber(table.getTableNumber());
			copiedTable.setDesc(table.getDesc());
			copiedTable.setCategory(table.getCategory());
			copiedTable.setManualPos(table.isManualPos());
			copiedTable.setPosX(table.getPosX());
			copiedTable.setPosY(table.getPosY());
			copiedTable.setWidth(table.getWidth());
			copiedTable.setHeight(table.getHeight());
			copiedTable.setRotation(table.getRotation());
			for (Seat seat : table.getSeats()) {
				Seat copiedSeat = new Seat();
				copiedSeat.setParent(copiedTable);
				copiedSeat.setSeatNumber(seat.getSeatNumber());
				copiedSeat.setWheelchairAccessible(seat.isWheelchairAccessible());
				copiedSeat.setManualPos(seat.isManualPos());
				copiedSeat.setPosX(seat.getPosX());
				copiedSeat.setPosY(seat.getPosY());
				copiedSeat.setWidth(seat.getWidth());
				copiedSeat.setHeight(seat.getHeight());
				copiedTable.getSeats().add(copiedSeat);
			}
			copy.getTables().add(copiedTable);
		}
		return copy;
	}

	/** Tische im Geltungsbereich der Reihe in der Reihenfolge Reihenliste, dann bisherige Nummer. */
	private List<Table> tablesInScope(TableRow row) {
		List<TableRow> scopeRows = new ArrayList<>();
		if (isUnlabeled(row)) {
			for (TableRow other : rows) {
				if (isUnlabeled(other)) {
					scopeRows.add(other);
				}
			}
		} else {
			scopeRows.add(row);
		}

		List<Table> scope = new ArrayList<>();
		for (TableRow scopeRow : scopeRows) {
			scope.addAll(getTablesOf(scopeRow));
		}
		return scope;
	}

	private static boolean isUnlabeled(TableRow row) {
		return row.getLabel() == null || row.getLabel().isBlank();
	}

	@Override
	public String toString() {
		return name != null ? name : "Unbenannte Bestuhlung";
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		SeatingPlan plan = (SeatingPlan) o;
		return Objects.equals(id, plan.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}
}
