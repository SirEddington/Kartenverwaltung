package de.eltviller_carneval_verein.karten.model;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Ein Sitz eines {@link Table Tisches}.
 * <p>
 * Verworfene Felder (nie in die unbekannten Felder aufnehmen, nie wieder für etwas anderes verwenden): {@code height}
 * (v1.0.x schreibt sie noch, seit KARV-128 gibt es keine Höhe mehr) und die alten Verkaufsdaten aus der Zeit vor v0.9.5
 * ({@code price}, {@code paymentStatus}, {@code collected}, {@code lastName}, {@code firstName}, {@code email},
 * {@code comment}); verkauft wird über {@link Booking}.
 */
@JsonIgnoreProperties(value = { "height", "price", "paymentStatus", "collected", "lastName", "firstName", "email",
		"comment" }, ignoreUnknown = true)
public class Seat {
	/** Radius eines Sitzes ohne Breite (cm), siehe {@link #radius()}. */
	public static final double FALLBACK_RADIUS = 10;

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
	// Sitze sind Kreise: Die Breite ist ihr Durchmesser. Eine Höhe gibt es nicht (mehr); "height" älterer Dateien wird
	// beim Laden übergangen. Der Name "width" bleibt, damit Datei und ältere Programmversionen dasselbe Feld lesen.
	private double width;

	// Konstuktoren -->
	public Seat() {
		this.id = UUID.randomUUID().toString();
	}

	public Seat(String id) {
		this.id = (id != null) ? id : UUID.randomUUID().toString();
	}
	// <-- Konstuktoren

	// Unbekannte Felder -->
	/** Felder aus der Datei, die diese Version nicht kennt (z. B. von einer neueren Version); sie werden unverändert zurückgeschrieben. */
	private final UnknownFields unknown = new UnknownFields();

	@JsonIgnore
	public UnknownFields getUnknown() {
		return unknown;
	}

	/** Merkt sich ein Feld, das diese Version nicht kennt (auch {@code requires}: es hat hier keine Wirkung). */
	@JsonAnySetter
	void putUnknown(String key, JsonNode value) {
		unknown.put(key, value);
	}

	@JsonAnyGetter
	Map<String, JsonNode> unknownFields() {
		return unknown.asMap();
	}
	// <-- Unbekannte Felder

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
	// <-- Getter und Setter

	/**
	 * Radius des Sitzes in cm: Sitze sind Kreise, die Breite ist ihr Durchmesser. Ohne Breite (0, negativ oder keine
	 * Zahl) bekäme der Sitz keine Fläche; Anordnung, Zeichnung und Prüfung rechnen dann mit {@value #FALLBACK_RADIUS}.
	 * Bewusst kein {@code getRadius}: Es soll kein Feld der Datei werden.
	 */
	public double radius() {
		return width > 0 ? width / 2.0 : FALLBACK_RADIUS;
	}

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
