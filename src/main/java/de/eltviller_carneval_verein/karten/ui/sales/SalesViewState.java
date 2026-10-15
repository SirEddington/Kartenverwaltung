package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import javafx.beans.property.ObjectProperty;
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
	private final ObservableSet<Seat> selectedSeats = FXCollections.observableSet(new LinkedHashSet<>());

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

	/** Die ausgewählten Sitze der aktiven Ansicht (Tabellenzeilen oder Sitze im Saalplan). */
	public ObservableSet<Seat> selectedSeats() {
		return selectedSeats;
	}

	/** Setzt die Auswahl auf {@code seats}: entfernt fehlende und fügt neue hinzu, gemeinsame Sitze bleiben unberührt. */
	public void replaceSelection(Collection<Seat> seats) {
		Set<Seat> wanted = new LinkedHashSet<>(seats);
		selectedSeats.retainAll(wanted);
		selectedSeats.addAll(wanted);
	}

	/** Beim Wechsel der Ansicht: leert die Auswahl und den Ausschnitt, Event und Vorstellung bleiben. */
	public void resetView() {
		selectedSeats.clear();
		visibleHallRect.set(null);
	}
}
