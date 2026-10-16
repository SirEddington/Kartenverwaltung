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

	@JsonManagedReference("hall-plan")
	private List<SeatingPlan> seatingPlans = new ArrayList<>();

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

	/** Nimmt jeden Wert an; ein leerer Name wird beim Speichern und live gemeldet (HLL-001). */
	public void setName(String name) {
		this.name = name;
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

	public List<SeatingPlan> getSeatingPlans() {
		return seatingPlans;
	}

	public void setSeatingPlans(List<SeatingPlan> seatingPlans) {
		this.seatingPlans = seatingPlans;
		if (seatingPlans != null) {
			seatingPlans.forEach(p -> p.setParent(this));
		}
	}

	/** Legt eine neue, leere Bestuhlung mit dem ersten freien Namen "Bestuhlung n" an. */
	public SeatingPlan addSeatingPlan() {
		SeatingPlan newPlan = new SeatingPlan();
		newPlan.setParent(this);
		newPlan.setName(createSeatingPlanName());

		seatingPlans.add(newPlan);
		return newPlan;
	}

	/** Die Bestuhlung mit dieser Id; {@code null} bei unbekannter oder fehlender Id. */
	public SeatingPlan findSeatingPlan(String id) {
		if (id == null) {
			return null;
		}
		for (SeatingPlan plan : seatingPlans) {
			if (id.equals(plan.getId())) {
				return plan;
			}
		}
		return null;
	}

	private String createSeatingPlanName() {
		Set<String> existingNames = seatingPlans.stream().map(SeatingPlan::getName).collect(Collectors.toSet());

		int i = 1;
		while (existingNames.contains("Bestuhlung " + i)) {
			i++;
		}

		return "Bestuhlung " + i;
	}

	public HallObject addHallObject() {
		HallObject newObject = new HallObject();
		newObject.setParent(this);
		newObject.setName(createHallObjectName());

		hallObjects.add(newObject);
		return newObject;
	}

	public HallObject addHallObject(String name) {
		HallObject newObject = new HallObject();
		newObject.setParent(this);

		if (!getHallObjectNames().contains(name)) {
			newObject.setName(name);
		} else {
			newObject.setName(createHallObjectName());
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
