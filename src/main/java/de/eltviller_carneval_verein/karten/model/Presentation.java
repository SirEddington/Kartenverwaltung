package de.eltviller_carneval_verein.karten.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;

public class Presentation {
	private final String id;
	@JsonBackReference("event-presentation")
	private Event parentEvent;

	private String name; // get
	private LocalDate date; // get,set
	private LocalTime time; // get,set
	private String description; // get, set

	// Daten für Saalansicht
	private double defaultTableWidth;
	private double defaultTableHeight;
	private double defaultSeatWidth;
	private double defaultSeatHeight;

	@JsonManagedReference("presentation-table")
	private List<Table> tables = new ArrayList<>(); // get,set

	// Konstuktoren -->
	public Presentation() {
		this.id = UUID.randomUUID().toString();
	}

	public Presentation(String id) {
		this.id = (id != null) ? id : UUID.randomUUID().toString();
	}
	// <-- Konstuktoren

	// Getter und Setter -->
	public String getId() {
		return id;
	}

	@JsonIgnore
	public Event getParent() {
		return parentEvent;
	}

	@JsonIgnore
	public void setParent(Event parentEvent) {
		this.parentEvent = parentEvent;
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
		if (tables != null) {
			tables.forEach(t -> t.setParent(this));
		}
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
	// <-- Getter und Setter

	public void changeName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Name darf nicht leer sein.");
		} else {
			this.name = name;
		}
	}

	public Table addTable() {
		Table newTable = new Table();
		newTable.setParent(this);
		newTable.changeTableNumber(createTableNumber());
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

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Presentation pres = (Presentation) o;
		return Objects.equals(id, pres.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}
}
