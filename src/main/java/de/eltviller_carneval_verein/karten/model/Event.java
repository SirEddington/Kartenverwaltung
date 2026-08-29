package de.eltviller_carneval_verein.karten.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class Event {
	private String name; // get
	private boolean archived;
	private List<Presentation> presentations = new ArrayList<>(); // get,set

	// Konstuktoren -->
	public Event() {
	} // Leerer Konstruktor ist Pflicht für Jackson

	public Event(String name) {
		this.name = name;
	}
	// <-- Konstuktoren

	// Getter und Setter -->
	public String getName() {
		return name;
	}

	public boolean isArchived() {
		return archived;
	}

	public void setArchived(boolean archived) {
		this.archived = archived;
	}

	public List<Presentation> getPresentations() {
		return presentations;
	}

	public void setPresentations(List<Presentation> presentations) {
		this.presentations = presentations;
	}
	// <-- Getter und Setter

	public void changeName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Name darf nicht leer sein.");
		} else {
			this.name = name;
		}
	}

	public void addPresentation(Presentation presentation) {
		if (presentation == null) {
			throw new IllegalArgumentException("Presentation ist null");
		}
		for (Presentation pres : presentations) {
			if (pres.getName() == presentation.getName()) {
				throw new IllegalArgumentException("Presentation existiert bereits");
			}
		}
		presentations.add(presentation);
	}

	@Override
	public String toString() {
		return name != null ? name : "Unbenanntes Event";
	}

	@JsonIgnore
	public List<Seat> getSeats() {
		List<Seat> seats = new ArrayList<Seat>();
		for (Presentation presentation : presentations) {
			seats.addAll(presentation.getSeats());
		}
		return seats;
	}

	@JsonIgnore
	public List<Integer> getSeatNumbers() {
		List<Integer> seatNumbers = new ArrayList<Integer>();
		for (Presentation presentation : presentations) {
			seatNumbers.addAll(presentation.getSeatNumbers());
		}
		return seatNumbers.stream().distinct().sorted().toList();
	}

	@JsonIgnore
	public List<Table> getTables() {
		List<Table> tables = new ArrayList<Table>();
		for (Presentation presentation : presentations) {
			tables.addAll(presentation.getTables());
		}
		return tables;
	}

	@JsonIgnore
	public List<Integer> getTableNumbers() {
		List<Integer> tableNumbers = new ArrayList<Integer>();
		for (Presentation presentation : presentations) {
			tableNumbers.addAll(presentation.getTableNumbers());
		}
		return tableNumbers.stream().distinct().sorted().toList();
	}
}
