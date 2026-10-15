package de.eltviller_carneval_verein.karten.model;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class HallObject {
	private final String id;
	@JsonBackReference("hall-hallObject")
	private Hall parentHall;

	private String name;
	private String desc;

	private double posX;
	private double posY;
	private double width;
	private double height;
	private String color;
	private Shape shape = Shape.RECTANGLE;

	// Text im Objekt (Größe in cm wie alle Hallenmaße, Drehung in Grad im Uhrzeigersinn). Mit textAuto bestimmt die
	// Anzeige die übrigen Werte selbst aus Größe und Form des Objekts; sie gelten dann nur als Vorbelegung.
	private boolean textAuto = true;
	private double textSize = 20;
	private TextAlignH textAlignH = TextAlignH.CENTER;
	private TextAlignV textAlignV = TextAlignV.CENTER;
	private double textRotation = 0;

	// Konstuktoren -->
	public HallObject() {
		this.id = UUID.randomUUID().toString();
	}

	public HallObject(String id) {
		this.id = (id != null) ? id : UUID.randomUUID().toString();
	}
	// <-- Konstuktoren

	// Getter und Setter -->
	public String getId() {
		return id;
	}

	@JsonIgnore
	public Hall getParent() {
		return parentHall;
	}

	@JsonIgnore
	public void setParent(Hall parentHall) {
		this.parentHall = parentHall;
	}

	public String getName() {
		return name;
	}

	/** Nimmt jeden Wert an; ein leerer Name wird beim Speichern und live gemeldet (HOB-001). */
	public void setName(String name) {
		this.name = name;
	}

	public String getDesc() {
		return desc;
	}

	public void setDesc(String desc) {
		this.desc = desc;
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

	public String getColor() {
		return color;
	}

	public void setColor(String color) {
		this.color = color;
	}

	public Shape getShape() {
		return shape;
	}

	public void setShape(Shape shape) {
		this.shape = shape;
	}

	public boolean isTextAuto() {
		return textAuto;
	}

	public void setTextAuto(boolean textAuto) {
		this.textAuto = textAuto;
	}

	public double getTextSize() {
		return textSize;
	}

	public void setTextSize(double textSize) {
		this.textSize = textSize;
	}

	public TextAlignH getTextAlignH() {
		return textAlignH;
	}

	public void setTextAlignH(TextAlignH textAlignH) {
		this.textAlignH = textAlignH;
	}

	public TextAlignV getTextAlignV() {
		return textAlignV;
	}

	public void setTextAlignV(TextAlignV textAlignV) {
		this.textAlignV = textAlignV;
	}

	public double getTextRotation() {
		return textRotation;
	}

	public void setTextRotation(double textRotation) {
		this.textRotation = textRotation;
	}
	// <-- Getter und Setter

	@Override
	public String toString() {
		return name != null ? name : "Unbenanntes Hallenobjekt";
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		HallObject hallObject = (HallObject) o;
		return Objects.equals(id, hallObject.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}
}
