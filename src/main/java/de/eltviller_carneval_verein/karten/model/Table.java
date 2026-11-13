package de.eltviller_carneval_verein.karten.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Table implements Labeled {
	private final String id;
	private SeatingPlan parentPlan; // gesetzt von SeatingPlan.setTables/addTable, wird nicht gespeichert

	private String rowId; // get,set
	private int tableNumber; // get
	private String description; // get,set
	private String category; // get,set

	// Daten für Saalansicht
	private boolean manualPos;
	private double posX;
	private double posY;
	private double width;
	private double height;
	private double rotation;

	// Beschriftung wie bei HallObject (Größe in cm, Drehung in Grad im Uhrzeigersinn ab der Tischachse). Mit textAuto
	// bestimmt die Anzeige die übrigen Werte selbst aus der Größe des Tischs; sie gelten dann nur als Vorbelegung.
	private boolean textAuto = true;
	private double textSize = 20;
	// Die Ausrichtungen vertragen einen Namen, den erst eine neuere Version kennt (Rückfallwert, Originaltext bleibt)
	private final TolerantEnum<TextAlignH> textAlignH = new TolerantEnum<>(TextAlignH.class, TextAlignH.CENTER, TextAlignH.CENTER);
	private final TolerantEnum<TextAlignV> textAlignV = new TolerantEnum<>(TextAlignV.class, TextAlignV.CENTER, TextAlignV.CENTER);
	private double textRotation = 0;

	// Den Elternverweis setzt setSeats (keine @JsonManagedReference, siehe Event.presentations)
	private List<Seat> seats = new ArrayList<Seat>(); // get,set

	// Konstuktoren -->
	public Table() {
		this.id = UUID.randomUUID().toString();
	}

	public Table(String id) {
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
	public SeatingPlan getPlan() {
		return parentPlan;
	}

	@JsonIgnore
	public void setPlan(SeatingPlan parentPlan) {
		this.parentPlan = parentPlan;
	}

	public String getRowId() {
		return rowId;
	}

	public void setRowId(String rowId) {
		this.rowId = rowId;
	}

	public double getRotation() {
		return rotation;
	}

	public void setRotation(double rotation) {
		this.rotation = rotation;
	}

	/** Der Buchstabe der Reihe ohne Rand; leer ohne Reihe, ohne Buchstaben oder ohne Bestuhlung. */
	@JsonIgnore
	public String getRowLabel() {
		TableRow row = parentPlan != null ? parentPlan.findRow(rowId) : null;
		String label = row != null ? row.getLabel() : null;
		return label != null ? label.trim() : "";
	}

	/** "Tisch A3" bei einer Reihe mit Buchstaben, sonst "Tisch 3". */
	@JsonIgnore
	public String getDisplayName() {
		return "Tisch " + getRowLabel() + tableNumber;
	}

	public int getTableNumber() {
		return tableNumber;
	}

	public String getDesc() {
		return description;
	}

	public void setDesc(String description) {
		this.description = description;
	}

	public String getCategory() {
		return category;
	}

	public void setCategory(String category) {
		this.category = category;
	}

	public List<Seat> getSeats() {
		return seats;
	}

	@JsonSetter(nulls = Nulls.AS_EMPTY) // "seats": null in einer Datei lädt als leere Liste
	public void setSeats(List<Seat> seats) {
		this.seats = seats;
		if (seats != null) {
			seats.forEach(t -> t.setParent(this));
		}
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

	public boolean isTextAuto() {
		return textAuto;
	}

	public void setTextAuto(boolean textAuto) {
		this.textAuto = textAuto;
	}

	/** Nimmt jeden Wert an; eine Größe außerhalb von 1 bis 1.000 meldet der {@code TableValidator} (TBL-006). */
	public double getTextSize() {
		return textSize;
	}

	public void setTextSize(double textSize) {
		this.textSize = textSize;
	}

	@JsonIgnore
	public TextAlignH getTextAlignH() {
		return textAlignH.get();
	}

	@JsonIgnore
	public void setTextAlignH(TextAlignH textAlignH) {
		this.textAlignH.set(textAlignH);
	}

	/** Der Name in der Datei: der Originaltext, wenn die Datei eine Ausrichtung nennt, die diese Version nicht kennt. */
	@JsonProperty("textAlignH")
	String textAlignHJson() {
		return textAlignH.toJson();
	}

	@JsonProperty("textAlignH")
	void textAlignHJson(String text) {
		textAlignH.fromJson(text);
	}

	@JsonIgnore
	public TextAlignV getTextAlignV() {
		return textAlignV.get();
	}

	@JsonIgnore
	public void setTextAlignV(TextAlignV textAlignV) {
		this.textAlignV.set(textAlignV);
	}

	@JsonProperty("textAlignV")
	String textAlignVJson() {
		return textAlignV.toJson();
	}

	@JsonProperty("textAlignV")
	void textAlignVJson(String text) {
		textAlignV.fromJson(text);
	}

	public double getTextRotation() {
		return textRotation;
	}

	public void setTextRotation(double textRotation) {
		this.textRotation = textRotation;
	}

	@Override
	@JsonIgnore
	public TextSettings getTextSettings() {
		return new TextSettings(textAuto, textSize, textAlignH.get(), textAlignV.get(), textRotation);
	}

	/** Setzt die Ausrichtungen über die Setter: Eine unveränderte Ausrichtung behält ihren Originaltext. */
	@Override
	@JsonIgnore
	public void setTextSettings(TextSettings settings) {
		textAuto = settings.auto();
		textSize = settings.size();
		setTextAlignH(settings.alignH());
		setTextAlignV(settings.alignV());
		textRotation = settings.rotation();
	}

	/** Übernimmt alle Textfelder von {@code source}, auch die Originaltexte unbekannter Ausrichtungen (für die Kopie der Bestuhlung). */
	void copyTextFrom(Table source) {
		textAuto = source.textAuto;
		textSize = source.textSize;
		textAlignH.copyFrom(source.textAlignH);
		textAlignV.copyFrom(source.textAlignV);
		textRotation = source.textRotation;
	}
	// <-- Getter und Setter

	/** Nimmt jeden Wert an; eine Nummer unter 1 wird vom {@code TableValidator} gemeldet (TBL-002). */
	public void setTableNumber(int tableNumber) {
		this.tableNumber = tableNumber;
	}

	public Seat addSeat() {
		Seat newSeat = new Seat();
		newSeat.setParent(this);
		newSeat.setSeatNumber(createSeatNumber());

		initSeatSize(newSeat);

		seats.add(newSeat);
		return newSeat;
	}

	public Seat addSeat(int seatNumber) {
		Seat newSeat = new Seat();
		newSeat.setParent(this);

		if (!getSeatNumbers().contains(seatNumber)) {
			newSeat.setSeatNumber(seatNumber);
		} else {
			newSeat.setSeatNumber(createSeatNumber());
		}

		initSeatSize(newSeat);

		seats.add(newSeat);
		return newSeat;
	}

	/** Übernimmt den Sitzdurchmesser aus der Bestuhlung; ohne Bestuhlung bleibt er 0. */
	private void initSeatSize(Seat seat) {
		if (parentPlan != null) {
			seat.setWidth(parentPlan.getDefaultSeatWidth());
		}
	}

	private int createSeatNumber() {
		// 1. Alle aktuell vorhandenen Namen einsammeln
		Set<Integer> existingNumbers = seats.stream().map(Seat::getSeatNumber).collect(Collectors.toSet());

		// 2. Ersten freien Namen finden
		int i = 1;
		while (existingNumbers.contains(i)) {
			i++;
		}

		return i;
	}
	
	public boolean deleteSeat(Seat seat) {
		return seats.remove(seat);
	}

	@Override
	public String toString() {
		return "Tisch " + tableNumber + (category != null ? "(" + category + ")" : "");
	}

	@JsonIgnore
	public List<Integer> getSeatNumbers() {
		List<Integer> seatNumbers = new ArrayList<Integer>();
		for (Seat seat : seats) {
			seatNumbers.add(seat.getSeatNumber());
		}
		return seatNumbers.stream().distinct().sorted().toList();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Table table = (Table) o;
		return Objects.equals(id, table.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}
}
