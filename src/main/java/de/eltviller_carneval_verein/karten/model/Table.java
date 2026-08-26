package de.eltviller_carneval_verein.karten.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class Table {
	private int tableNumber; // get
	private String description; // get,set
	private String category; // get,set
	private List<Seat> seats = new ArrayList<Seat>(); // get,set

	// Konstuktoren -->
	public Table() {
	} // Leerer Konstruktor ist Pflicht für Jackson

	public Table(int tableNumber) {
		this.tableNumber = tableNumber;
	}
	// <-- Konstuktoren

	// Getter und Setter -->
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
	}
	// <-- Getter und Setter

	public void changeTableNumber(int tableNumber) {
		if (tableNumber <= 0) {
			throw new IllegalArgumentException("Tischnummer darf nicht kleiner als 1 sein.");
		} else {
			this.tableNumber = tableNumber;
		}
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
}
