package de.eltviller_carneval_verein.karten.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;

public class Event {
	private final String id;
	
	private String name;
	private String description;
	private boolean archived;
	
	@JsonManagedReference("event-presentation")
	private List<Presentation> presentations = new ArrayList<>(); // get,set

	// Konstuktoren -->
	public Event() {
		this.id = UUID.randomUUID().toString();
	}
	
	public Event(String id) {
		this.id = (id != null) ? id : UUID.randomUUID().toString();
	}
	// <-- Konstuktoren

	// Getter und Setter -->
	public String getId() {
		return id;
	}
	
	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
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
		if (presentations != null) {
	        presentations.forEach(p -> p.setParent(this));
	    }
	}
	// <-- Getter und Setter

	public void changeName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Name darf nicht leer sein.");
		} else {
			this.name = name;
		}
	}

	public Presentation addPresentation() {
		Presentation newPres = new Presentation();
		newPres.setParent(this);
		newPres.changeName(createPresName());
		presentations.add(newPres);
		return newPres;
	}

	// ToDo addPresentation mit Name

	private String createPresName() {
		// 1. Alle aktuell vorhandenen Namen einsammeln
		Set<String> existingNames = presentations.stream().map(Presentation::getName).collect(Collectors.toSet());

		// 2. Ersten freien Namen finden
		int i = 1;
		while (existingNames.contains("Vorstellung " + i)) {
			i++;
		}

		return "Vorstellung " + i;
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
	
	@Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Event event = (Event) o;
        return Objects.equals(id, event.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
