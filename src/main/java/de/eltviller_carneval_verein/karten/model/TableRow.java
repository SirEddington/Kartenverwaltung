package de.eltviller_carneval_verein.karten.model;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Eine Tischreihe einer {@link SeatingPlan Bestuhlung}: Anordnung (Lage, Winkel, Abstände) und Kennbuchstabe. Die
 * Tische einer Reihe verweisen über {@link Table#getRowId()} auf sie. Wertebereiche prüft der Validator, die Setter
 * nehmen jeden Wert an.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TableRow {
	private final String id;
	@JsonBackReference("plan-row")
	private SeatingPlan parentPlan;

	/** Kennbuchstabe der Reihe; leer erlaubt (dann zählen die Tische über alle Reihen ohne Buchstaben). */
	private String label = "";

	// Anordnung in der Halle
	private double posX;
	private double posY;
	private double angle;
	private double tableRotation;
	private double tableGap;
	private double aisleWidth;
	private Integer aisleAfter;

	// Konstuktoren -->
	public TableRow() {
		this.id = UUID.randomUUID().toString();
	}

	public TableRow(String id) {
		this.id = (id != null) ? id : UUID.randomUUID().toString();
	}
	// <-- Konstuktoren

	// Getter und Setter -->
	public String getId() {
		return id;
	}

	@JsonIgnore
	public SeatingPlan getParent() {
		return parentPlan;
	}

	@JsonIgnore
	public void setParent(SeatingPlan parentPlan) {
		this.parentPlan = parentPlan;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public double getPosX() {
		return posX;
	}

	public void setPosX(double posX) {
		this.posX = posX;
	}

	public double getPosY() {
		return posY;
	}

	public void setPosY(double posY) {
		this.posY = posY;
	}

	public double getAngle() {
		return angle;
	}

	public void setAngle(double angle) {
		this.angle = angle;
	}

	public double getTableRotation() {
		return tableRotation;
	}

	public void setTableRotation(double tableRotation) {
		this.tableRotation = tableRotation;
	}

	public double getTableGap() {
		return tableGap;
	}

	public void setTableGap(double tableGap) {
		this.tableGap = tableGap;
	}

	public double getAisleWidth() {
		return aisleWidth;
	}

	public void setAisleWidth(double aisleWidth) {
		this.aisleWidth = aisleWidth;
	}

	public Integer getAisleAfter() {
		return aisleAfter;
	}

	public void setAisleAfter(Integer aisleAfter) {
		this.aisleAfter = aisleAfter;
	}
	// <-- Getter und Setter

	@Override
	public String toString() {
		return (label == null || label.isBlank()) ? "Reihe" : "Reihe " + label;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		TableRow row = (TableRow) o;
		return Objects.equals(id, row.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}
}
