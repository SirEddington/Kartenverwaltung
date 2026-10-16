package de.eltviller_carneval_verein.karten.model;

import java.util.Comparator;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * Die Buchung eines Sitzes in einer {@link Presentation Vorstellung}: Verkaufsdaten plus eine Momentaufnahme des
 * Platzes (Reihenbuchstabe, Tisch- und Sitznummer, Kategorie, Rollstuhlkennzeichen). Die Buchung zeigt über
 * {@link #getSeatId()} auf den Sitz der Bestuhlung; ändert sich die Bestuhlung, bleibt die Momentaufnahme lesbar.
 * Die Setter nehmen jeden Wert an.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Booking {
	/** Reihenfolge der Plätze: Reihenbuchstabe (leer zuerst), dann Tischnummer, dann Sitznummer. */
	public static final Comparator<Booking> PLACE_ORDER = Comparator.comparing(Booking::sortLabel)
			.thenComparingInt(Booking::getTableNumber).thenComparingInt(Booking::getSeatNumber);

	@JsonBackReference("presentation-booking")
	private Presentation parentPresentation;

	private String seatId; // get,set

	// Momentaufnahme des Platzes
	private String rowLabel = ""; // get,set
	private int tableNumber; // get,set
	private int seatNumber; // get,set
	private String category; // get,set
	private boolean wheelchairAccessible; // get,set

	/** Einzelpreis in Cent; {@code null} heißt: es gilt der Standardpreis der Vorstellung. */
	private Integer price; // get,set
	private final ObjectProperty<PaymentStatus> paymentStatus = new SimpleObjectProperty<>(PaymentStatus.NONE); // get,set
	private boolean collected; // get,set

	// Daten zum Reservierer
	private String lastName; // get,set
	private String firstName; // get,set
	private String eMail; // get,set
	private String comment; // get,set

	/** Nur zur Laufzeit: der Sitz der Buchung gehört nicht (mehr) zur Bestuhlung. Wird nicht gespeichert. */
	private boolean unassigned;

	// Getter und Setter -->
	@JsonIgnore
	public Presentation getParent() {
		return parentPresentation;
	}

	@JsonIgnore
	public void setParent(Presentation parentPresentation) {
		this.parentPresentation = parentPresentation;
	}

	public String getSeatId() {
		return seatId;
	}

	public void setSeatId(String seatId) {
		this.seatId = seatId;
	}

	public String getRowLabel() {
		return rowLabel;
	}

	public void setRowLabel(String rowLabel) {
		this.rowLabel = rowLabel;
	}

	public int getTableNumber() {
		return tableNumber;
	}

	public void setTableNumber(int tableNumber) {
		this.tableNumber = tableNumber;
	}

	public int getSeatNumber() {
		return seatNumber;
	}

	public void setSeatNumber(int seatNumber) {
		this.seatNumber = seatNumber;
	}

	public String getCategory() {
		return category;
	}

	public void setCategory(String category) {
		this.category = category;
	}

	public boolean isWheelchairAccessible() {
		return wheelchairAccessible;
	}

	public void setWheelchairAccessible(boolean wheelchairAccessible) {
		this.wheelchairAccessible = wheelchairAccessible;
	}

	public Integer getPrice() {
		return price;
	}

	public void setPrice(Integer price) {
		this.price = price;
	}

	@JsonIgnore
	public ObjectProperty<PaymentStatus> getPaymentStatusProperty() {
		return paymentStatus;
	}

	public PaymentStatus getPaymentStatus() {
		return paymentStatus.get();
	}

	public void setPaymentStatus(PaymentStatus paymentStatus) {
		this.paymentStatus.set(paymentStatus);
	}

	public boolean isCollected() {
		return collected;
	}

	public void setCollected(boolean collected) {
		this.collected = collected;
	}

	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastName = lastName;
	}

	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}

	public String getEMail() {
		return eMail;
	}

	public void setEMail(String eMail) {
		this.eMail = eMail;
	}

	public String getComment() {
		return comment;
	}

	public void setComment(String comment) {
		this.comment = comment;
	}

	@JsonIgnore
	public boolean isUnassigned() {
		return unassigned;
	}

	@JsonIgnore
	public void setUnassigned(boolean unassigned) {
		this.unassigned = unassigned;
	}
	// <-- Getter und Setter

	/** Der Preis, der für diese Buchung gilt: der Einzelpreis, sonst der Standardpreis der Vorstellung, ohne Vorstellung 0. */
	@JsonIgnore
	public int getEffectivePrice() {
		if (price != null) {
			return price;
		}
		return parentPresentation != null ? parentPresentation.getPrice() : 0;
	}

	/** Setzt den Einzelpreis; ist der Wert gleich dem Standardpreis der Vorstellung, gilt wieder der Standardpreis. */
	@JsonIgnore
	public void setEffectivePrice(int cents) {
		if (parentPresentation != null && cents == parentPresentation.getPrice()) {
			this.price = null;
		} else {
			this.price = cents;
		}
	}

	@JsonIgnore
	public boolean isReserved() {
		return lastName != null && !lastName.isBlank();
	}

	@JsonIgnore
	public boolean isPaid() {
		return getPaymentStatus() != null && getPaymentStatus().isPaid();
	}

	@JsonIgnore
	public SeatStatus getStatus() {
		if (isPaid()) {
			return SeatStatus.SOLD;
		}
		if (isReserved()) {
			return SeatStatus.RESERVED;
		}
		return SeatStatus.FREE;
	}

	/**
	 * Ob die Buchung Daten trägt, die nicht verloren gehen dürfen: Name, E-Mail, Kommentar, ein Zahlstatus oder
	 * abgeholt. Ein bloßer Einzelpreis zählt nicht.
	 */
	public boolean hasData() {
		return !isBlank(lastName) || !isBlank(firstName) || !isBlank(eMail) || !isBlank(comment)
				|| (getPaymentStatus() != null && getPaymentStatus() != PaymentStatus.NONE) || collected;
	}

	/** "Tisch A3" bei einer Reihe mit Buchstaben, sonst "Tisch 3" (aus der Momentaufnahme). */
	@JsonIgnore
	public String getTableLabel() {
		return "Tisch " + sortLabel() + tableNumber;
	}

	/** "Tisch A3, Sitz 2" (aus der Momentaufnahme). */
	@JsonIgnore
	public String getPlaceLabel() {
		return getTableLabel() + ", Sitz " + seatNumber;
	}

	/** Merkt sich den Sitz und hält den Platz (Reihe, Tisch, Sitz, Kategorie, Rollstuhl) als Momentaufnahme fest. */
	public void takeSnapshot(Table table, Seat seat) {
		this.seatId = seat.getId();
		SeatingPlan plan = table.getPlan();
		TableRow row = plan != null ? plan.findRow(table.getRowId()) : null;
		this.rowLabel = row != null && row.getLabel() != null ? row.getLabel() : "";
		this.tableNumber = table.getTableNumber();
		this.seatNumber = seat.getSeatNumber();
		this.category = table.getCategory();
		this.wheelchairAccessible = seat.isWheelchairAccessible();
	}

	/** Kopie aller gespeicherten Felder; ohne Vorstellung als Elternteil und ohne die Laufzeitmarkierung. */
	public Booking copy() {
		Booking copy = new Booking();
		copy.seatId = seatId;
		copy.rowLabel = rowLabel;
		copy.tableNumber = tableNumber;
		copy.seatNumber = seatNumber;
		copy.category = category;
		copy.wheelchairAccessible = wheelchairAccessible;
		copy.price = price;
		copy.setPaymentStatus(getPaymentStatus());
		copy.collected = collected;
		copy.lastName = lastName;
		copy.firstName = firstName;
		copy.eMail = eMail;
		copy.comment = comment;
		return copy;
	}

	/** Reihenbuchstabe ohne Rand, {@code null} als leer. */
	private String sortLabel() {
		return rowLabel != null ? rowLabel.trim() : "";
	}

	private static boolean isBlank(String text) {
		return text == null || text.isBlank();
	}

	@Override
	public String toString() {
		return getPlaceLabel();
	}
}
