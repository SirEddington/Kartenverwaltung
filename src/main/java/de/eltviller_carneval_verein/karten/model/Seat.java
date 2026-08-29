package de.eltviller_carneval_verein.karten.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class Seat {
	private int seatNumber; // get
	private int price; // get,set
	private boolean paid; // get,set
	private boolean collected; // get,set
	private boolean wheelchairAccessible; // get,set

	// Daten zum Reservierer
	private String lastName; // get,set
	private String firstName; // get,set
	private String eMail; // get,set

	private String comment; // get,set

	// Konstuktoren -->
	public Seat() {
	} // Leerer Konstruktor ist Pflicht für Jackson

	public Seat(int seatNumber) {
		this.seatNumber = seatNumber;
	}

	public Seat(int seatNumber, boolean wheelchairAccessible) {
		this(seatNumber);
		this.wheelchairAccessible = wheelchairAccessible;
	}
	// <-- Konstuktoren

	// Getter und Setter -->
	public int getSeatNumber() {
		return seatNumber;
	}

	public int getPrice() {
		return price;
	}

	@JsonIgnore
	public double getPriceDouble() {
		return price / 100.0;
	}

	public void setPrice(int price) {
		this.price = price;
	}

	@JsonIgnore
	public void setPriceDouble(double price) {
		this.price = (int) Math.round(price * 100);
	}

	public boolean isPaid() {
		return paid;
	}

	public void setPaid(boolean paid) {
		this.paid = paid;
	}

	public boolean isCollected() {
		return collected;
	}

	public void setCollected(boolean collected) {
		this.collected = collected;
	}

	public boolean isWheelchairAccessible() {
		return wheelchairAccessible;
	}

	public void setWheelchairAccessible(boolean wheelchairAccessible) {
		this.wheelchairAccessible = wheelchairAccessible;
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
	// <-- Getter und Setter

	public void changeSeatNumber(int seatNumber) {
		if (seatNumber <= 0) {
			throw new IllegalArgumentException("Sitznummer darf nicht kleiner als 1 sein.");
		} else {
			this.seatNumber = seatNumber;
		}
	}

	@JsonIgnore
	public boolean isReserved() {
		if (lastName == null || lastName.isBlank()) {
			return false;
		} else {
			return true;
		}
	}

	@Override
	public String toString() {
		return "Sitz " + seatNumber + (wheelchairAccessible == true ? "Rollstuhlgeeignet" : "");
	}

}
