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
	@JsonBackReference("presentation-table")
	private Presentation parentPresentation;

	private int tableNumber; // get
	private String description; // get,set
	private String category; // get,set

	// Daten für Saalansicht
	private boolean manualPos;
	private double posX;
	private double posY;
	private double width;
	private double height;

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
	public Presentation getParent() {
		return parentPresentation;
	}

	@JsonIgnore
	public void setParent(Presentation parentPresentation) {
		this.parentPresentation = parentPresentation;
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

	public void changeTableNumber(int tableNumber) {
		if (tableNumber <= 0) {
			throw new IllegalArgumentException("Tischnummer darf nicht kleiner als 1 sein.");
		} else {
			this.tableNumber = tableNumber;
		}
	}

	public Seat addSeat() {
		Seat newSeat = new Seat();
		newSeat.setParent(this);
		newSeat.changeSeatNumber(createSeatNumber());
		
		// Maße initialisieren
		newSeat.setHeight(getParent().getDefaultSeatHeight());
		newSeat.setWidth(getParent().getDefaultSeatWidth());
		
		seats.add(newSeat);
		return newSeat;
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
