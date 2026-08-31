package de.eltviller_carneval_verein.karten.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class Table {
	private int tableNumber; // get
	private String description; // get,set
	private String category; // get,set
	private List<Seat> seats = new ArrayList<Seat>(); // get,set
	@JsonIgnore
	private Presentation parentPresentation;

	// Konstuktoren -->
	public Table() {
	} // Leerer Konstruktor ist Pflicht für Jackson

	public Table(Presentation parentPres, int tableNumber) {
		this.parentPresentation = parentPres;
		this.tableNumber = tableNumber;
	}
	// <-- Konstuktoren

	// Getter und Setter -->
	@JsonIgnore
	public Presentation getParent() {
		return parentPresentation;
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
		Seat newSeat = new Seat(this, createSeatNumber());
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
}
