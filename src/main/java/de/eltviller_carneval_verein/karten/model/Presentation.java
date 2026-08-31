package de.eltviller_carneval_verein.karten.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class Presentation {
	private String name; // get
	private LocalDate date; // get,set
	private LocalTime time; // get,set
	private String description; // get, set
	private List<Table> tables = new ArrayList<>(); // get,set
	@JsonIgnore
	private Event parentEvent;

	// Konstuktoren -->
	public Presentation() {
	} // Leerer Konstruktor ist Pflicht für Jackson

	public Presentation(Event parentEvent, String name) {
		this.parentEvent = parentEvent;
		this.name = name;
	}
	// <-- Konstuktoren

	// Getter und Setter -->
	@JsonIgnore
	public Event getParent() {
		return parentEvent;
	}

	public String getName() {
		return name;
	}

	public LocalDate getDate() {
		return date;
	}

	public void setDate(LocalDate date) {
		this.date = date;
	}

	public LocalTime getTime() {
		return time;
	}

	public void setTime(LocalTime time) {
		this.time = time;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
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

	public Table addTable() {
		Table newTable = new Table(this, createTableNumber());
		tables.add(newTable);
		return newTable;
	}

	// ToDO addTable mit TableNumber

	private int createTableNumber() {
		// 1. Alle aktuell vorhandenen Namen einsammeln
		Set<Integer> existingNumbers = tables.stream().map(Table::getTableNumber).collect(Collectors.toSet());

		// 2. Ersten freien Namen finden
		int i = 1;
		while (existingNumbers.contains(i)) {
			i++;
		}

		return i;
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
