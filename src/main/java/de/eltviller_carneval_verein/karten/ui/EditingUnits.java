package de.eltviller_carneval_verein.karten.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.scene.control.TableView;

/**
 * Die Registratur der Objekte, für die gerade eine Eingabe offen ist (ein Popup, eine Zelle im Bearbeiten, der
 * Hallen-Editor im Bearbeitungsmodus). Ein fremder Stand für so ein Objekt wird zurückgestellt, weil die Eingabe noch
 * nicht im Modell steht und sonst vom Einarbeiten überschrieben würde. Die Repositories bekommen {@link #isEditing} als
 * {@code Predicate}, damit {@code repository} nicht von {@code ui} abhängt; wer eine Bearbeitung beendet, löst über
 * {@link #addEndListener} das erneute Einarbeiten aus.
 *
 * <p>
 * Objekte zählen nach Identität, nicht nach {@code equals}: Gemeint ist genau das Objekt im Modell, das die Eingabe
 * verändern wird. Nicht threadsicher: alle Aufrufe im JavaFX-Thread.
 */
public final class EditingUnits {

	private static final Logger LOG = Logger.getLogger(EditingUnits.class.getName());

	private static final EditingUnits INSTANCE = new EditingUnits();

	// Je Besitzer die Objekte, die er gerade bearbeitet (beide nach Identität)
	private final Map<Object, Set<Object>> byOwner = new IdentityHashMap<>();
	private final List<Runnable> endListeners = new ArrayList<>();

	/** Die Registratur der App; Tests erzeugen mit {@code new EditingUnits()} eine eigene. */
	public static EditingUnits getInstance() {
		return INSTANCE;
	}

	/** Für Tests, die ohne den Singleton auskommen wollen; die App nutzt {@link #getInstance()}. */
	public EditingUnits() {
	}

	/**
	 * Der Besitzer beginnt, die Modellobjekte zu bearbeiten. Was er vorher gemeldet hatte, ersetzt diese Meldung; fällt dabei
	 * ein Objekt weg, ist dessen Bearbeitung beendet, und die Endlistener laufen.
	 *
	 * @param owner  wer die Eingabe hält (Popup, Tabelle, Editor); dasselbe Objekt beendet sie mit {@link #end}
	 * @param models die Objekte im Modell, die die Eingabe verändern wird; {@code null}-Einträge zählen nicht
	 */
	public void begin(Object owner, Collection<?> models) {
		Set<Object> now = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Object model : models) {
			if (model != null) {
				now.add(model);
			}
		}
		Set<Object> before = now.isEmpty() ? byOwner.remove(owner) : byOwner.put(owner, now);
		if (before != null && !now.containsAll(before)) {
			fireEnd();
		}
	}

	/** Der Besitzer ist fertig (bestätigt, abgebrochen oder geschlossen). Hatte er nichts gemeldet, passiert nichts. */
	public void end(Object owner) {
		if (byOwner.remove(owner) != null) {
			fireEnd();
		}
	}

	/** {@code true}, wenn irgendein Besitzer dieses Objekt gerade bearbeitet. */
	public boolean isEditing(Object model) {
		if (model == null) {
			return false;
		}
		for (Set<Object> models : byOwner.values()) {
			if (models.contains(model)) {
				return true;
			}
		}
		return false;
	}

	/** Meldet einen Listener an, der aufgerufen wird, wenn eine Bearbeitung endet (auch für nur ein Objekt davon). */
	public void addEndListener(Runnable listener) {
		endListeners.add(listener);
	}

	/**
	 * Meldet die Zelle im Bearbeiten einer Tabelle: Beginnt die Bearbeitung einer Zelle, zählt das Modellobjekt ihrer Zeile
	 * als in Bearbeitung ({@code begin(table, [modelOf(item)])}); bestätigt oder bricht der Nutzer ab, endet es
	 * ({@code end(table)}).
	 *
	 * @param modelOf liefert zum Element der Zeile das Objekt im Modell, das die Zelle ändert; {@code null}, wenn es keins gibt
	 */
	public <S> void watch(TableView<S> table, Function<S, ?> modelOf) {
		table.editingCellProperty().addListener((obs, before, cell) -> {
			if (cell == null) {
				end(table);
				return;
			}
			int row = cell.getRow();
			Object model = row >= 0 && row < table.getItems().size() ? modelOf.apply(table.getItems().get(row)) : null;
			begin(table, model == null ? List.of() : List.of(model));
		});
	}

	private void fireEnd() {
		for (Runnable listener : new ArrayList<>(endListeners)) {
			try {
				listener.run();
			} catch (RuntimeException e) {
				// Das Ende einer Bearbeitung ist geschehen; ein Listener darf es nicht rückgängig aussehen lassen
				LOG.log(Level.SEVERE, "Listener am Ende einer Bearbeitung ist fehlgeschlagen", e);
			}
		}
	}
}
