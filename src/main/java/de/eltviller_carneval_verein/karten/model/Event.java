package de.eltviller_carneval_verein.karten.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Event {
	private final String id;

	private String name;
	private String description;
	private boolean archived;

	// Den Elternverweis setzt setPresentations; keine @JsonManagedReference, denn Jackson 2.17 verträgt sie nicht mit
	// @JsonSetter(nulls = AS_EMPTY) ("Should never try to reset delegate")
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

	@JsonSetter(nulls = Nulls.AS_EMPTY) // "presentations": null in einer Datei lädt als leere Liste
	public void setPresentations(List<Presentation> presentations) {
		this.presentations = presentations;
		if (presentations != null) {
			presentations.forEach(p -> p.setParent(this));
		}
	}
	// <-- Getter und Setter

	/** Nimmt jeden Wert an; ein leerer Name wird vom {@code EventValidator} gemeldet (EVT-002). */
	public void setName(String name) {
		this.name = name;
	}

	public Presentation addPresentation() {
		Presentation newPres = new Presentation();
		newPres.setParent(this);
		newPres.setName(createPresName());
		presentations.add(newPres);
		return newPres;
	}

	public Presentation addPresentation(String presentationName) {
		Presentation newPres = new Presentation();
		newPres.setParent(this);

		if (!getPresentationNames().contains(presentationName)) {
			newPres.setName(presentationName);
		} else {
			newPres.setName(createPresName());
		}

		presentations.add(newPres);
		return newPres;
	}

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
	
	public boolean deletePresentation(Presentation presentation) {
		return presentations.remove(presentation);
	}

	/**
	 * Name für die Kopie eines Events ("Event kopieren"): "&lt;Name&gt; (Kopie)", bei Kollision mit einem vorhandenen
	 * Namen "&lt;Name&gt; (Kopie 2)", "(Kopie 3)" usw. Groß-/Kleinschreibung und Leerzeichen am Rand zählen beim Vergleich
	 * nicht. Ein Event ohne Namen heißt "Event".
	 */
	public static String createCopyName(String name, Collection<String> existingNames) {
		String base = (name == null || name.isBlank()) ? "Event" : name.trim();
		Set<String> taken = existingNames.stream().filter(Objects::nonNull).map(n -> n.trim().toLowerCase(Locale.GERMAN)).collect(Collectors.toSet());
		String candidate = base + " (Kopie)";
		for (int i = 2; taken.contains(candidate.toLowerCase(Locale.GERMAN)); i++) {
			candidate = base + " (Kopie " + i + ")";
		}
		return candidate;
	}

	@Override
	public String toString() {
		return name != null ? name : "Unbenanntes Event";
	}

	/** Alle Buchungen aller Vorstellungen. */
	@JsonIgnore
	public List<Booking> getBookings() {
		List<Booking> bookings = new ArrayList<Booking>();
		for (Presentation presentation : presentations) {
			bookings.addAll(presentation.getBookings());
		}
		return bookings;
	}

	@JsonIgnore
	public List<String> getPresentationNames() {
		List<String> presNames = new ArrayList<String>();
		for (Presentation pres : presentations) {
			presNames.add(pres.getName());
		}
		return presNames.stream().distinct().sorted().toList();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Event event = (Event) o;
		return Objects.equals(id, event.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}
}
