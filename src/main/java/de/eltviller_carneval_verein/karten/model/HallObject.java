package de.eltviller_carneval_verein.karten.model;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class HallObject {
	private final String id;
	@JsonBackReference("presentation-hallObject")
	private Presentation parentPresentation;

	private String name;
	private String desc;

	private double posX;
	private double posY;
	private double width;
	private double height;
	private String color;
	private Shape shape;

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
	public Presentation getParent() {
		return parentPresentation;
	}

	@JsonIgnore
	public void setParent(Presentation parentPresentation) {
		this.parentPresentation = parentPresentation;
	}

	public String getName() {
		return name;
	}

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
	// <-- Getter und Setter

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
