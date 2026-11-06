package de.eltviller_carneval_verein.karten.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/**
 * Eine Bestuhlung einer {@link Hall Halle}: Tischreihen, Tische und Sitze. Die Setter nehmen jeden Wert an; die
 * fachlichen Regeln stehen in den Validatoren.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SeatingPlan {
	private final String id;
	private Hall parentHall; // gesetzt von Hall.setSeatingPlans/addSeatingPlan, wird nicht gespeichert

	private String name;
	private String description;

	/** Standardmaße einer neuen Bestuhlung und Ersatz für fehlende Maße (cm): Tisch 50 × 320, Sitz 45 (KARV-128). */
	public static final double STANDARD_TABLE_WIDTH = 50;
	public static final double STANDARD_TABLE_HEIGHT = 320;
	public static final double STANDARD_SEAT_WIDTH = 45;

	/** Das Maß {@code value}, oder {@code standard}, wenn es fehlt (0 oder kleiner). */
	public static double orStandard(double value, double standard) {
		return value > 0 ? value : standard;
	}

	// Standardmaße für neue Tische und Sitze. Sitze sind Kreise: defaultSeatWidth ist ihr Durchmesser; eine
	// "defaultSeatHeight" älterer Dateien wird beim Laden übergangen.
	private double defaultTableWidth;
	private double defaultTableHeight;
	private double defaultSeatWidth;

	// Die Elternverweise setzen setRows und setTables (keine @JsonManagedReference, siehe Event.presentations)
	private List<TableRow> rows = new ArrayList<>();

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

	public List<TableRow> getRows() {
		return rows;
	}

	@JsonSetter(nulls = Nulls.AS_EMPTY) // "rows": null in einer Datei lädt als leere Liste
	public void setRows(List<TableRow> rows) {
		this.rows = rows;
		if (rows != null) {
			rows.forEach(r -> r.setParent(this));
		}
	}

	public List<Table> getTables() {
		return tables;
	}

	@JsonSetter(nulls = Nulls.AS_EMPTY) // "tables": null in einer Datei lädt als leere Liste
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

	/**
	 * Die Tische der Reihe in der Reihenfolge des Anlegens: So stehen sie in der Reihe. Die Nummer ist nur Beschriftung;
	 * eine neue (auch doppelte) Nummer verschiebt den Tisch nicht.
	 */
	public List<Table> getTablesOf(TableRow row) {
		String rowId = row.getId();
		return tables.stream().filter(t -> Objects.equals(t.getRowId(), rowId)).toList();
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
	 * innerhalb einer Reihe nach dem Platz ({@link #getTablesOf}).
	 */
	public void renumber(TableRow row) {
		int number = 1;
		for (Table table : tablesInScope(row)) {
			table.setTableNumber(number++);
		}
	}

	/**
	 * Wie {@link #fillRow(TableRow, int, int)} mit diesen Maßen (cm): Sie werden zuerst die Standardmaße der Bestuhlung
	 * und gelten so auch für Tische und Sitze, die später einzeln dazukommen.
	 */
	public void fillRow(TableRow row, int tableCount, int seatsPerTable, double tableWidth, double tableHeight, double seatWidth) {
		defaultTableWidth = tableWidth;
		defaultTableHeight = tableHeight;
		defaultSeatWidth = seatWidth;
		fillRow(row, tableCount, seatsPerTable);
	}

	/** Legt {@code tableCount} Tische mit je {@code seatsPerTable} Sitzen hinten an die Reihe an (Standardmaße). */
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
	 * Sitze, die {@code rowId} der kopierten Tische zeigt auf die kopierten Reihen. Von den Tischen werden auch die
	 * Texteinstellungen übernommen, von den Sitzen Nummer, Rollstuhlkennzeichen, Position und Durchmesser; Buchungen
	 * gehören zur Vorstellung und bleiben unberührt.
	 */
	public SeatingPlan copy() {
		SeatingPlan copy = new SeatingPlan();
		copy.setName(name);
		copy.setDescription(description);
		copy.setDefaultTableWidth(defaultTableWidth);
		copy.setDefaultTableHeight(defaultTableHeight);
		copy.setDefaultSeatWidth(defaultSeatWidth);

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
			copiedTable.setTextSettings(table.getTextSettings());
			for (Seat seat : table.getSeats()) {
				Seat copiedSeat = new Seat();
				copiedSeat.setParent(copiedTable);
				copiedSeat.setSeatNumber(seat.getSeatNumber());
				copiedSeat.setWheelchairAccessible(seat.isWheelchairAccessible());
				copiedSeat.setManualPos(seat.isManualPos());
				copiedSeat.setPosX(seat.getPosX());
				copiedSeat.setPosY(seat.getPosY());
				copiedSeat.setWidth(seat.getWidth());
				copiedTable.getSeats().add(copiedSeat);
			}
			copy.getTables().add(copiedTable);
		}
		return copy;
	}

	/** Tische im Geltungsbereich der Reihe in der Reihenfolge Reihenliste, dann Platz in der Reihe. */
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
