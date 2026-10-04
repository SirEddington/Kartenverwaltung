package de.eltviller_carneval_verein.karten.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonManagedReference;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Hall {
	private final String id;

	private String name;
	private String description;

	// Daten für Saalansicht
	private double hallWidth;
	private double hallHeight;

	@JsonManagedReference("hall-hallObject")
	private List<HallObject> hallObjects = new ArrayList<>();

	// Konstuktoren -->
	public Hall() {
		this.id = UUID.randomUUID().toString();
	}

	public Hall(String id) {
		this.id = (id != null) ? id : UUID.randomUUID().toString();
	}
	// <-- Konstuktoren

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public void changeName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Name darf nicht leer sein.");
		} else {
			this.name = name;
		}
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public double getHallWidth() {
		return hallWidth;
	}

	public void setHallWidth(double hallWidth) {
		this.hallWidth = hallWidth;
	}

	public double getHallHeight() {
		return hallHeight;
	}

	public void setHallHeight(double hallHeight) {
		this.hallHeight = hallHeight;
	}

	public List<HallObject> getHallObjects() {
		return hallObjects;
	}

	public void setHallObjects(List<HallObject> hallObjects) {
		this.hallObjects = hallObjects;
		if (hallObjects != null) {
			hallObjects.forEach(t -> t.setParent(this));
		}
	}

	public HallObject addHallObject() {
		HallObject newObject = new HallObject();
		newObject.setParent(this);
		newObject.changeName(createHallObjectName());

		hallObjects.add(newObject);
		return newObject;
	}

	public HallObject addHallObject(String name) {
		HallObject newObject = new HallObject();
		newObject.setParent(this);

		if (!getHallObjectNames().contains(name)) {
			newObject.changeName(name);
		} else {
			newObject.changeName(createHallObjectName());
		}

		hallObjects.add(newObject);
		return newObject;
	}

	private String createHallObjectName() {
		// 1. Alle aktuell vorhandenen Namen einsammeln
		Set<String> existingNames = hallObjects.stream().map(HallObject::getName).collect(Collectors.toSet());

		// 2. Ersten freien Namen finden
		int i = 1;
		while (existingNames.contains("Hallenobjekt " + i)) {
			i++;
		}

		return "Hallenobjekt " + i;
	}

	@JsonIgnore
	public List<String> getHallObjectNames() {
		List<String> objectNames = new ArrayList<String>();
		for (HallObject object : hallObjects) {
			objectNames.add(object.getName());
		}
		return objectNames.stream().distinct().sorted().toList();
	}

	@Override
	public String toString() {
		return name != null ? name : "Unbenannte Halle";
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Hall hall = (Hall) o;
		return Objects.equals(id, hall.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}
}
