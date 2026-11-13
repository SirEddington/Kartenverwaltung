package de.eltviller_carneval_verein.karten.model;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

// Ab v1.1 übergeht das Laden unbekannte Felder; v1.0.x lehnt sie hier noch ab, darum schreibt das Hallenobjekt keine neuen
@JsonIgnoreProperties(ignoreUnknown = true)
public class HallObject implements Labeled {
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
	// Form und Ausrichtungen vertragen einen Namen, den erst eine neuere Version kennt (Rückfallwert, Originaltext bleibt)
	private final TolerantEnum<Shape> shape = new TolerantEnum<>(Shape.class, Shape.RECTANGLE, Shape.RECTANGLE);

	// Text im Objekt (Größe in cm wie alle Hallenmaße, Drehung in Grad im Uhrzeigersinn). Mit textAuto bestimmt die
	// Anzeige die übrigen Werte selbst aus Größe und Form des Objekts; sie gelten dann nur als Vorbelegung.
	private boolean textAuto = true;
	private double textSize = 20;
	private final TolerantEnum<TextAlignH> textAlignH = new TolerantEnum<>(TextAlignH.class, TextAlignH.CENTER, TextAlignH.CENTER);
	private final TolerantEnum<TextAlignV> textAlignV = new TolerantEnum<>(TextAlignV.class, TextAlignV.CENTER, TextAlignV.CENTER);
	private double textRotation = 0;

	// Konstuktoren -->
	public HallObject() {
		this.id = UUID.randomUUID().toString();
	}

	public HallObject(String id) {
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

	@JsonIgnore
	public Shape getShape() {
		return shape.get();
	}

	@JsonIgnore
	public void setShape(Shape shape) {
		this.shape.set(shape);
	}

	/** Der Name in der Datei: der Originaltext, wenn die Datei eine Form nennt, die diese Version nicht kennt. */
	@JsonProperty("shape")
	String shapeJson() {
		return shape.toJson();
	}

	@JsonProperty("shape")
	void shapeJson(String text) {
		shape.fromJson(text);
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

	@JsonIgnore
	public TextAlignH getTextAlignH() {
		return textAlignH.get();
	}

	@JsonIgnore
	public void setTextAlignH(TextAlignH textAlignH) {
		this.textAlignH.set(textAlignH);
	}

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
