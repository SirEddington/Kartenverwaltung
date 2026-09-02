package de.eltviller_carneval_verein.karten.model;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class Seat {
	private final String id;
	@JsonBackReference("table-seat")
	private Table parentTable;

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

	// Daten für Saalansicht
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
