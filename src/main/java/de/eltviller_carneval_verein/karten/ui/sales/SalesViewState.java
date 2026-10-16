package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import javafx.geometry.Rectangle2D;

/**
 * Der gemeinsame Zustand der Verkaufsansicht: Das Hauptfenster (Shell, Kartentabelle, Saalübersicht) schreibt ihn,
 * das Kundenfenster beobachtet ihn nur.
 */
public final class SalesViewState {

	private final ObjectProperty<Event> event = new SimpleObjectProperty<>();
	private final ObjectProperty<Presentation> presentation = new SimpleObjectProperty<>();
	private final ObjectProperty<Rectangle2D> visibleHallRect = new SimpleObjectProperty<>();
	private final ObservableSet<Booking> selectedBookings = FXCollections.observableSet(new LinkedHashSet<>());
	private final BooleanProperty showRows = new SimpleBooleanProperty(true);

	public ObjectProperty<Event> eventProperty() {
		return event;
	}

	public ObjectProperty<Presentation> presentationProperty() {
		return presentation;
	}

	/** Der sichtbare Ausschnitt der Saalübersicht in Hallenkoordinaten (cm), {@code null} wenn sie nicht sichtbar ist. */
	public ObjectProperty<Rectangle2D> visibleHallRectProperty() {
		return visibleHallRect;
	}

	/** Die ausgewählten Buchungen der aktiven Ansicht (Tabellenzeilen oder Sitze im Saalplan). */
	public ObservableSet<Booking> selectedBookings() {
		return selectedBookings;
	}

	/**
	 * Ob der Saalplan die Reihen (Linie mit Buchstabe) zeigt; Tische und Sitze sind immer zu sehen. Standard an, gilt
	 * für Saalübersicht und Kundenfenster und bleibt beim Wechsel der Ansicht erhalten.
	 */
	public BooleanProperty showRowsProperty() {
		return showRows;
	}

	/** Setzt die Auswahl auf {@code bookings}: entfernt fehlende und fügt neue hinzu, gemeinsame bleiben unberührt. */
	public void replaceSelection(Collection<Booking> bookings) {
		Set<Booking> wanted = new LinkedHashSet<>(bookings);
		selectedBookings.retainAll(wanted);
		selectedBookings.addAll(wanted);
	}

	/** Beim Wechsel der Ansicht: leert die Auswahl und den Ausschnitt, Event, Vorstellung und "Reihen anzeigen" bleiben. */
	public void resetView() {
		selectedBookings.clear();
		visibleHallRect.set(null);
	}
}
