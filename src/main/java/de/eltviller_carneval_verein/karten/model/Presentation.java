package de.eltviller_carneval_verein.karten.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonManagedReference;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Presentation {
	private final String id;
	@JsonBackReference("event-presentation")
	private Event parentEvent;

	private String name;
	private LocalDate date;
	private LocalTime time;
	private String description;

	// Referenz auf eine wiederverwendbare Halle (siehe Hall-Klasse). Bewusst nur die ID,
	// damit mehrere Vorstellungen - auch über verschiedene Events hinweg - dieselbe Halle
	// referenzieren können, ohne dass Jackson sie beim Speichern mehrfach einbettet.
	private String hallId;

	// Bestuhlung der Halle, die diese Vorstellung nutzt (nur die ID, siehe hallId), und Standardpreis in Cent
	private String seatingPlanId; // get,set
	private int price; // get,set

	// Standardwerte
	private double defaultTableWidth;
	private double defaultTableHeight;
	private double defaultSeatWidth;
	private double defaultSeatHeight;

	// Kassenabgleich
	private int actualCashAmount; // tatsächlicher Bar-Kasseninhalt in Cent
	private String cashDifferenceComment; // Erklärung bei Kassen-Differenz

	@JsonManagedReference("presentation-table")
	private List<Table> tables = new ArrayList<>(); // get,set

	@JsonManagedReference("presentation-booking")
	private List<Booking> bookings = new ArrayList<>(); // get,set

	/** Nur zur Laufzeit: die Bestuhlung der Vorstellung gibt es nicht (mehr) in der Halle. Wird nicht gespeichert. */
	private boolean planMissing;

	// Konstuktoren -->
	public Presentation() {
		this.id = UUID.randomUUID().toString();
	}

	public Presentation(String id) {
		this.id = (id != null) ? id : UUID.randomUUID().toString();
	}
	// <-- Konstuktoren

	// Getter und Setter -->
	public String getId() {
		return id;
	}

	@JsonIgnore
	public Event getParent() {
		return parentEvent;
	}

	@JsonIgnore
	public void setParent(Event parentEvent) {
		this.parentEvent = parentEvent;
	}

	public String getName() {
		return name;
	}

	public LocalDate getDate() {
		return date;
	}

	public void setDate(LocalDate date) {
		this.date = date;
	}

	public LocalTime getTime() {
		return time;
	}

	public void setTime(LocalTime time) {
		this.time = time;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public List<Table> getTables() {
		return tables;
	}

	public void setTables(List<Table> tables) {
		this.tables = tables;
		if (tables != null) {
			tables.forEach(t -> t.setParent(this));
		}
	}

	public String getSeatingPlanId() {
		return seatingPlanId;
	}

	public void setSeatingPlanId(String seatingPlanId) {
		this.seatingPlanId = seatingPlanId;
	}

	/** Standardpreis pro Karte in Cent. */
	public int getPrice() {
		return price;
	}

	/** Setzt nur das Feld (Jackson ruft es beim Laden); für Änderungen durch den Nutzer {@link #changePrice(int)}. */
	public void setPrice(int price) {
		this.price = price;
	}

	public List<Booking> getBookings() {
		return bookings;
	}

	public void setBookings(List<Booking> bookings) {
		this.bookings = bookings;
		if (bookings != null) {
			bookings.forEach(b -> b.setParent(this));
		}
	}

	@JsonIgnore
	public boolean isPlanMissing() {
		return planMissing;
	}

	@JsonIgnore
	public void setPlanMissing(boolean planMissing) {
		this.planMissing = planMissing;
	}

	public double getDefaultTableWidth() {
		return defaultTableWidth;
	}

	public void setDefaultTableWidth(double defaultTableWidth) {
		this.defaultTableWidth = defaultTableWidth;
	}

	public double getDefaultTableHeight() {
		return defaultTableHeight;
	}

	public void setDefaultTableHeight(double defaultTableHeight) {
		this.defaultTableHeight = defaultTableHeight;
	}

	public double getDefaultSeatWidth() {
		return defaultSeatWidth;
	}

	public void setDefaultSeatWidth(double defaultSeatWidth) {
		this.defaultSeatWidth = defaultSeatWidth;
	}

	public double getDefaultSeatHeight() {
		return defaultSeatHeight;
	}

	public void setDefaultSeatHeight(double defaultSeatHeight) {
		this.defaultSeatHeight = defaultSeatHeight;
	}

	public int getActualCashAmount() {
		return actualCashAmount;
	}

	public void setActualCashAmount(int actualCashAmount) {
		this.actualCashAmount = actualCashAmount;
	}

	@JsonIgnore
	public double getActualCashAmountDouble() {
		return actualCashAmount / 100.0;
	}

	@JsonIgnore
	public void setActualCashAmountDouble(double actualCashAmount) {
		this.actualCashAmount = (int) Math.round(actualCashAmount * 100);
	}

	public String getCashDifferenceComment() {
		return cashDifferenceComment;
	}

	public void setCashDifferenceComment(String cashDifferenceComment) {
		this.cashDifferenceComment = cashDifferenceComment;
	}
	// <-- Getter und Setter

	public String getHallId() {
		return hallId;
	}

	public void setHallId(String hallId) {
		this.hallId = hallId;
	}

	/** Nimmt jeden Wert an; ein leerer Name wird vom {@code PresentationValidator} gemeldet (PRS-004). */
	public void setName(String name) {
		this.name = name;
	}

	public Table addTable() {
		Table newTable = new Table();
		newTable.setParent(this);
		newTable.setTableNumber(createTableNumber());

		// Maße initialisieren
		newTable.setHeight(defaultTableHeight);
		newTable.setWidth(defaultTableWidth);

		tables.add(newTable);
		return newTable;
	}

	public Table addTable(int tableNumber) {
		Table newTable = new Table();
		newTable.setParent(this);

		if (!getTableNumbers().contains(tableNumber)) {
			newTable.setTableNumber(tableNumber);
		} else {
			newTable.setTableNumber(createTableNumber());
		}

		// Maße initialisieren
		newTable.setHeight(defaultTableHeight);
		newTable.setWidth(defaultTableWidth);

		tables.add(newTable);
		return newTable;
	}

	private int createTableNumber() {
		// 1. Alle aktuell vorhandenen Nummern einsammeln
		Set<Integer> existingNumbers = tables.stream().map(Table::getTableNumber).collect(Collectors.toSet());

		// 2. Ersten freien Namen finden
		int i = 1;
		while (existingNumbers.contains(i)) {
			i++;
		}

		return i;
	}
	
	public boolean deleteTable(Table table) {
		return tables.remove(table);
	}

	public Booking addBooking() {
		Booking newBooking = new Booking();
		newBooking.setParent(this);
		bookings.add(newBooking);
		return newBooking;
	}

	/**
	 * Ändert den Standardpreis. Buchungen mit Daten ({@link Booking#hasData()}), die noch den Standardpreis
	 * verwenden, behalten vorher den bisherigen Preis als Einzelpreis; leere Buchungen folgen dem neuen Preis.
	 */
	public void changePrice(int newPrice) {
		if (newPrice == price) {
			return;
		}
		for (Booking booking : bookings) {
			if (booking.hasData() && booking.getPrice() == null) {
				booking.setPrice(price);
			}
		}
		this.price = newPrice;
	}

	@Override
	public String toString() {
		return name != null ? name : "Unbenannte Vorstellung";
	}

	@JsonIgnore
	public List<Seat> getSeats() {
		List<Seat> seats = new ArrayList<>();
		for (Table table : tables) {
			seats.addAll(table.getSeats());
		}
		return seats;
	}

	@JsonIgnore
	public List<Integer> getSeatNumbers() {
		List<Integer> seatNumbers = new ArrayList<Integer>();
		for (Table table : tables) {
			seatNumbers.addAll(table.getSeatNumbers());
		}
		return seatNumbers.stream().distinct().sorted().toList();
	}

	@JsonIgnore
	public List<Integer> getTableNumbers() {
		List<Integer> tableNumbers = new ArrayList<Integer>();
		for (Table table : tables) {
			tableNumbers.add(table.getTableNumber());
		}
		return tableNumbers.stream().distinct().sorted().toList();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Presentation pres = (Presentation) o;
		return Objects.equals(id, pres.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}
}
