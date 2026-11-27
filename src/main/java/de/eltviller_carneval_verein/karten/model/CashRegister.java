package de.eltviller_carneval_verein.karten.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Eine Kasse eines {@link Event Events} (KARV-144): Vorstellungskasse (Kennung der Vorstellung), gemeinsame Kasse
 * ({@link #SHARED_ID}) oder freie Kasse ({@code _} und UUID). Die Art folgt aus der Kennung, siehe {@link #kind()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CashRegister {

	/** Die Art der Kasse; sie wird nicht gespeichert, sondern folgt aus der Kennung. */
	public enum Kind {
		PRESENTATION, SHARED, FREE
	}

	/** Kennung der gemeinsamen Kasse eines Events. */
	public static final String SHARED_ID = "_event";

	private static final String FREE_PREFIX = "_";

	private final String id;

	private String name;
	private boolean allPresentations;
	private List<String> presentationIds = new ArrayList<>();
	private int actualCashAmount; // tatsächlicher Bar-Kasseninhalt in Cent
	private String cashDifferenceComment; // Erklärung bei Kassen-Differenz

	// Konstuktoren -->
	public CashRegister() {
		this.id = newFreeId();
	}

	public CashRegister(String id) {
		this.id = (id != null) ? id : newFreeId();
	}
	// <-- Konstuktoren

	/** Eine neue Kennung für eine freie Kasse: {@code _} und eine UUID. */
	public static String newFreeId() {
		return FREE_PREFIX + UUID.randomUUID();
	}

	// Unbekannte Felder -->
	/** Felder aus der Datei, die diese Version nicht kennt (z. B. von einer neueren Version); sie werden unverändert zurückgeschrieben. */
	private final UnknownFields unknown = new UnknownFields();

	@JsonIgnore
	public UnknownFields getUnknown() {
		return unknown;
	}

	/** Merkt sich ein Feld, das diese Version nicht kennt; ein durchgelassenes {@code requires} fällt weg (siehe {@link UnknownFields#putUnitField}). */
	@JsonAnySetter
	void putUnknown(String key, JsonNode value) {
		unknown.putUnitField(key, value);
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

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public boolean isAllPresentations() {
		return allPresentations;
	}

	public void setAllPresentations(boolean allPresentations) {
		this.allPresentations = allPresentations;
	}

	public List<String> getPresentationIds() {
		return presentationIds;
	}

	@JsonSetter(nulls = Nulls.AS_EMPTY) // "presentationIds": null in einer Datei lädt als leere Liste
	public void setPresentationIds(List<String> presentationIds) {
		this.presentationIds = presentationIds != null ? presentationIds : new ArrayList<>();
	}

	public int getActualCashAmount() {
		return actualCashAmount;
	}

	public void setActualCashAmount(int actualCashAmount) {
		this.actualCashAmount = actualCashAmount;
	}

	public String getCashDifferenceComment() {
		return cashDifferenceComment;
	}

	public void setCashDifferenceComment(String cashDifferenceComment) {
		this.cashDifferenceComment = cashDifferenceComment;
	}
	// <-- Getter und Setter

	/** Die Art der Kasse nach der Kennung: {@code _event} gemeinsam, {@code _…} frei, sonst Vorstellungskasse. */
	@JsonIgnore
	public Kind kind() {
		if (SHARED_ID.equals(id)) {
			return Kind.SHARED;
		}
		return id.startsWith(FREE_PREFIX) ? Kind.FREE : Kind.PRESENTATION;
	}

	/** Übernimmt alle gespeicherten Felder eines anderen Stands außer der Kennung (unveränderlich). */
	public void copyFrom(CashRegister source) {
		setName(source.getName());
		setAllPresentations(source.isAllPresentations());
		setPresentationIds(source.getPresentationIds() != null ? new ArrayList<>(source.getPresentationIds()) : new ArrayList<>());
		setActualCashAmount(source.getActualCashAmount());
		setCashDifferenceComment(source.getCashDifferenceComment());
		unknown.replaceWith(source.unknown);
	}
}
