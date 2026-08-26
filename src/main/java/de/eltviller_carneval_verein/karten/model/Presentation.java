package de.eltviller_carneval_verein.karten.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class Presentation {
	private String name; // get
	private String date; // get,set
	private String time; // get,set
	private List<Table> tables = new ArrayList<>(); // get,set

	// Konstuktoren -->
	public Presentation() {
	} // Leerer Konstruktor ist Pflicht für Jackson

	public Presentation(String name) {
		this.name = name;
	}
	// <-- Konstuktoren

	// Getter und Setter -->
	public String getName() {
		return name;
	}

	public String getDate() {
		return date;
	}

	public void setDate(String date) {
		this.date = date;
	}

	public String getTime() {
		return time;
	}

	public void setTime(String time) {
		this.time = time;
	}

	public List<Table> getTables() {
		return tables;
	}

	public void setTables(List<Table> tables) {
		this.tables = tables;
	}
	// <-- Getter und Setter

	public void changeName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Name darf nicht leer sein.");
		} else {
			this.name = name;
		}
	}

	@Override
	public String toString() {
		return name != null ? name : "Unbenannte Vorstellung";
	}

	@JsonIgnore
	public List<Seat> getSeats() {
		List<Seat> seats = new ArrayList<>();
		for (Table table : tables) {
			seats.addAll(table.getSeats());
		}
		return seats;
	}

	@JsonIgnore
	public List<Integer> getSeatNumbers() {
		List<Integer> seatNumbers = new ArrayList<Integer>();
		for (Table table : tables) {
			seatNumbers.addAll(table.getSeatNumbers());
		}
		return seatNumbers.stream().distinct().sorted().toList();
	}

	@JsonIgnore
	public List<Integer> getTableNumbers() {
		List<Integer> tableNumbers = new ArrayList<Integer>();
		for (Table table : tables) {
			tableNumbers.add(table.getTableNumber());
		}
		return tableNumbers.stream().distinct().sorted().toList();
	}
}
