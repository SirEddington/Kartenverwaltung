package de.eltviller_carneval_verein.karten.ui.hall;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;

/**
 * Die einstellbaren Beschriftungen des Hallenplans, beobachtbar für das Menü "Anzeige" (siehe
 * {@link PlanLabelsMenu}). Alles ist beim Start an; die Einstellung wird nicht gespeichert. Die Saalansicht gibt sie über
 * den Verkaufszustand an das Kundenfenster weiter, der Hallen-Editor hat eigene.
 */
public final class PlanLabelOptions {

	private final BooleanProperty rows = new SimpleBooleanProperty(true);
	private final BooleanProperty tables = new SimpleBooleanProperty(true);
	private final BooleanProperty seatNumbers = new SimpleBooleanProperty(true);
	private final BooleanProperty hallObjects = new SimpleBooleanProperty(true);

	public BooleanProperty rowsProperty() {
		return rows;
	}

	public BooleanProperty tablesProperty() {
		return tables;
	}

	public BooleanProperty seatNumbersProperty() {
		return seatNumbers;
	}

	public BooleanProperty hallObjectsProperty() {
		return hallObjects;
	}

	/** Der aktuelle Stand, mit dem gezeichnet wird. */
	public PlanLabels snapshot() {
		return new PlanLabels(rows.get(), tables.get(), seatNumbers.get(), hallObjects.get());
	}

	/** Meldet jede Änderung einer der Einstellungen. */
	public void addListener(ChangeListener<? super Boolean> listener) {
		rows.addListener(listener);
		tables.addListener(listener);
		seatNumbers.addListener(listener);
		hallObjects.addListener(listener);
	}

	public void removeListener(ChangeListener<? super Boolean> listener) {
		rows.removeListener(listener);
		tables.removeListener(listener);
		seatNumbers.removeListener(listener);
		hallObjects.removeListener(listener);
	}
}
