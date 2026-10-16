package de.eltviller_carneval_verein.karten.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;

public class Table {
	private final String id;
	@JsonBackReference("plan-table")
	private SeatingPlan parentPlan;

	private String rowId; // get,set
	private int tableNumber; // get
	private String description; // get,set
	private String category; // get,set

	// Daten für Saalansicht
	private boolean manualPos;
	private double posX;
	private double posY;
	private double width;
	private double height;
	private double rotation;

	@JsonManagedReference("table-seat")
	private List<Seat> seats = new ArrayList<Seat>(); // get,set

	// Konstuktoren -->
	public Table() {
		this.id = UUID.randomUUID().toString();
	}

	public Table(String id) {
		this.id = (id != null) ? id : UUID.randomUUID().toString();
	}
	// <-- Konstuktoren

	// Getter und Setter -->
	public String getId() {
		return id;
	}

	@JsonIgnore
	public SeatingPlan getPlan() {
		return parentPlan;
	}

	@JsonIgnore
	public void setPlan(SeatingPlan parentPlan) {
		this.parentPlan = parentPlan;
	}

	public String getRowId() {
		return rowId;
	}

	public void setRowId(String rowId) {
		this.rowId = rowId;
	}

	public double getRotation() {
		return rotation;
	}

	public void setRotation(double rotation) {
		this.rotation = rotation;
	}

	/** "Tisch A3" bei einer Reihe mit Buchstaben, sonst "Tisch 3". */
	@JsonIgnore
	public String getDisplayName() {
		TableRow row = parentPlan != null ? parentPlan.findRow(rowId) : null;
		String label = row != null ? row.getLabel() : null;
		return "Tisch " + (label != null ? label.trim() : "") + tableNumber;
	}

	public int getTableNumber() {
		return tableNumber;
	}

	public String getDesc() {
		return description;
	}

	public void setDesc(String description) {
		this.description = description;
	}

	public String getCategory() {
		return category;
	}

	public void setCategory(String category) {
		this.category = category;
	}

	public List<Seat> getSeats() {
		return seats;
	}

	public void setSeats(List<Seat> seats) {
		this.seats = seats;
		if (seats != null) {
			seats.forEach(t -> t.setParent(this));
		}
	}

	public boolean isManualPos() {
		return manualPos;
	}

	public void setManualPos(boolean manualPos) {
		this.manualPos = manualPos;
	}

	public double getPosX() {
		return posX;
	}

	public void setPosX(double posX) {
		this.posX = posX;
	}

	public double getPosY() {
		return posY;
	}

	public void setPosY(double posY) {
		this.posY = posY;
	}

	public double getWidth() {
		return width;
	}

	public void setWidth(double width) {
		this.width = width;
	}

	public double getHeight() {
		return height;
	}

	public void setHeight(double height) {
		this.height = height;
	}
	// <-- Getter und Setter

	/** Nimmt jeden Wert an; eine Nummer unter 1 wird vom {@code TableValidator} gemeldet (TBL-002). */
	public void setTableNumber(int tableNumber) {
		this.tableNumber = tableNumber;
	}

	public Seat addSeat() {
		Seat newSeat = new Seat();
		newSeat.setParent(this);
		newSeat.setSeatNumber(createSeatNumber());

		initSeatSize(newSeat);

		seats.add(newSeat);
		return newSeat;
	}

	public Seat addSeat(int seatNumber) {
		Seat newSeat = new Seat();
		newSeat.setParent(this);

		if (!getSeatNumbers().contains(seatNumber)) {
			newSeat.setSeatNumber(seatNumber);
		} else {
			newSeat.setSeatNumber(createSeatNumber());
		}

		initSeatSize(newSeat);

		seats.add(newSeat);
		return newSeat;
	}

	/** Übernimmt die Sitzmaße aus der Bestuhlung; ohne Bestuhlung bleiben sie 0. */
	private void initSeatSize(Seat seat) {
		if (parentPlan != null) {
			seat.setHeight(parentPlan.getDefaultSeatHeight());
			seat.setWidth(parentPlan.getDefaultSeatWidth());
		}
	}

	private int createSeatNumber() {
		// 1. Alle aktuell vorhandenen Namen einsammeln
		Set<Integer> existingNumbers = seats.stream().map(Seat::getSeatNumber).collect(Collectors.toSet());

		// 2. Ersten freien Namen finden
		int i = 1;
		while (existingNumbers.contains(i)) {
			i++;
		}

		return i;
	}
	
	public boolean deleteSeat(Seat seat) {
		return seats.remove(seat);
	}

	@Override
	public String toString() {
		return "Tisch " + tableNumber + (category != null ? "(" + category + ")" : "");
	}

	@JsonIgnore
	public List<Integer> getSeatNumbers() {
		List<Integer> seatNumbers = new ArrayList<Integer>();
		for (Seat seat : seats) {
			seatNumbers.add(seat.getSeatNumber());
		}
		return seatNumbers.stream().distinct().sorted().toList();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Table table = (Table) o;
		return Objects.equals(id, table.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}
}
