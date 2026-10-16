package de.eltviller_carneval_verein.karten.model;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Seat {
	private final String id;
	private Table parentTable; // gesetzt von Table.setSeats/addSeat, wird nicht gespeichert

	private int seatNumber; // get
	private boolean wheelchairAccessible; // get,set

	// Ältere Dateien enthalten hier noch Verkaufsdaten (Preis, Zahlstatus, Name ...); dank ignoreUnknown werden sie beim
	// Laden übergangen. Verkauft wird über die Buchungen der Vorstellung (Booking).

	// Daten für Saalansicht
	private boolean manualPos;
	private double posX;
	private double posY;
	private double width;
	private double height;

	// Konstuktoren -->
	public Seat() {
		this.id = UUID.randomUUID().toString();
	}

	public Seat(String id) {
		this.id = (id != null) ? id : UUID.randomUUID().toString();
	}
	// <-- Konstuktoren

	// Getter und Setter -->
	public String getId() {
		return id;
	}

	@JsonIgnore
	public Table getParent() {
		return parentTable;
	}

	@JsonIgnore
	public void setParent(Table parentTable) {
		this.parentTable = parentTable;
	}

	public int getSeatNumber() {
		return seatNumber;
	}

	public boolean isWheelchairAccessible() {
		return wheelchairAccessible;
	}

	public void setWheelchairAccessible(boolean wheelchairAccessible) {
		this.wheelchairAccessible = wheelchairAccessible;
	}

	public boolean isManualPos() {
		return manualPos;
	}

	public void setManualPos(boolean manualPos) {
		this.manualPos = manualPos;
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

	public double getWidth() {
		return width;
	}

	public void setWidth(double width) {
		this.width = width;
	}

	public double getHeight() {
		return height;
	}

	public void setHeight(double height) {
		this.height = height;
	}
	// <-- Getter und Setter

	/** Nimmt jeden Wert an; eine Nummer unter 1 wird vom {@code SeatValidator} gemeldet (SEA-008). */
	public void setSeatNumber(int seatNumber) {
		this.seatNumber = seatNumber;
	}

	@Override
	public String toString() {
		return "Sitz " + seatNumber + (wheelchairAccessible == true ? "Rollstuhlgeeignet" : "");
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Seat seat = (Seat) o;
		return Objects.equals(id, seat.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

}
