package de.eltviller_carneval_verein.karten.ui.hall;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.Node;

/**
 * Die einstellbaren Beschriftungen des Hallenplans, beobachtbar für das Menü "Anzeige" (siehe
 * {@link PlanLabelsMenu}). Eine neue Instanz zeigt alles. Die App hat eine gemeinsame für Saalansicht, Kundenfenster
 * und Hallen-Editor ({@code DisplayPreferences}, KARV-76); eine Ansicht beobachtet sie deshalb mit
 * {@link #follow(Node, Runnable)}, damit die Optionen sie nicht über ihr Ende hinaus festhalten.
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

	/**
	 * Ruft {@code onChange} bei jeder Änderung, solange {@code owner} lebt: Der Knoten hält den Listener, die Optionen
	 * kennen ihn nur schwach. So hält eine app-weite Instanz eine geschlossene Ansicht nicht fest.
	 *
	 * @param owner    ein Knoten der beobachtenden Ansicht
	 * @param onChange zum Beispiel das Neuzeichnen des Plans
	 */
	public void follow(Node owner, Runnable onChange) {
		ChangeListener<Boolean> listener = (obs, oldValue, newValue) -> onChange.run();
		owner.getProperties().put(listener, listener);
		addListener(new WeakChangeListener<>(listener));
	}

	public void removeListener(ChangeListener<? super Boolean> listener) {
		rows.removeListener(listener);
		tables.removeListener(listener);
		seatNumbers.removeListener(listener);
		hallObjects.removeListener(listener);
	}
}
