package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.ui.DisplayPreferences;
import de.eltviller_carneval_verein.karten.ui.hall.PlanLabelOptions;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
	private final ObservableList<Booking> searchHits = FXCollections.observableArrayList();
	private final ObservableList<Booking> searchHitsView = FXCollections.unmodifiableObservableList(searchHits);
	// Der Inhalt, der gerade zu sehen ist: Nur er meldet Treffer (eine verlassene Ansicht kann noch zeichnen, KARV-192)
	private Object activeView;
	private final DisplayPreferences display;

	/**
	 * @param display die Auswahl im Menü "Anzeige"; in der App die gemeinsame ({@link DisplayPreferences#instance()}),
	 *                die auch der Hallen-Editor nutzt
	 */
	public SalesViewState(DisplayPreferences display) {
		this.display = display;
	}

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
	 * Welche Beschriftungen der Saalplan zeigt (Menü "Anzeige"): Reihen, Tischnamen, Sitznummern, Namen der
	 * Hallenobjekte. Gilt für Saalübersicht und Kundenfenster.
	 */
	public PlanLabelOptions planLabels() {
		return display.planLabels();
	}

	/**
	 * Ob Saalübersicht und Kundenfenster den Sitzstatus barrierefrei zeigen (Menü "Anzeige", KARV-26), siehe
	 * {@link SeatLook}.
	 */
	public BooleanProperty seatPatternsProperty() {
		return display.seatPatternsProperty();
	}

	/** Ob Anzahl und Preis der Auswahl am Mauszeiger stehen (Menü "Anzeige", KARV-194), siehe {@link SelectionHint}. */
	public BooleanProperty selectionHintProperty() {
		return display.selectionHintProperty();
	}

	/** Wo das Kundenfenster die Kartenübersicht zeigt (Menü "Anzeige"). */
	public ObjectProperty<SummaryPlacement> summaryPlacementProperty() {
		return display.summaryPlacementProperty();
	}

	/** Ob der Saalplan die Reihen (Linie mit Buchstabe) zeigt; Tische und Sitze sind immer zu sehen. */
	public BooleanProperty showRowsProperty() {
		return display.planLabels().rowsProperty();
	}

	/** Setzt die Auswahl auf {@code bookings}: entfernt fehlende und fügt neue hinzu, gemeinsame bleiben unberührt. */
	public void replaceSelection(Collection<Booking> bookings) {
		Set<Booking> wanted = new LinkedHashSet<>(bookings);
		selectedBookings.retainAll(wanted);
		selectedBookings.addAll(wanted);
	}

	/**
	 * Die Buchungen, die die aktive Ansicht gerade als Treffer der Suche zeigt, in ihrer Reihenfolge (KARV-192): die
	 * Zeilen der Kartentabelle bzw. die nicht abgeblendeten Sitze der Saalansicht. Nur lesbar; geschrieben wird über
	 * {@link #showSearchHits}.
	 */
	public ObservableList<Booking> searchHits() {
		return searchHitsView;
	}

	/** Die Shell meldet den Inhalt, der gerade zu sehen ist; nur seine Treffer zählen ({@link #showSearchHits}). */
	public void setActiveView(Object view) {
		activeView = view;
	}

	/**
	 * Ersetzt die Treffer, wenn {@code view} die aktive Ansicht ist; eine verlassene Ansicht, die noch zeichnet (etwa über
	 * das Menü "Anzeige"), ändert nichts.
	 */
	public void showSearchHits(Object view, List<Booking> hits) {
		if (view == activeView) {
			searchHits.setAll(hits);
		}
	}

	/**
	 * Beim Wechsel der Ansicht: leert die Auswahl, den Ausschnitt und die Treffer, Event, Vorstellung, die aktive Ansicht
	 * und "Reihen anzeigen" bleiben.
	 */
	public void resetView() {
		selectedBookings.clear();
		visibleHallRect.set(null);
		searchHits.clear();
	}
}
