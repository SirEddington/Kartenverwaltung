package de.eltviller_carneval_verein.karten.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.scene.control.ComboBox;

/**
 * Führt die Auswahl von Event und Vorstellung in den Kopf-Boxen einer Shell (Verkauf, Abrechnung) nach Änderungen anderer
 * Rechner nach. Die Auswahl bleibt über die ID: Ist das gewählte Event noch dasselbe Objekt, wird nichts neu belegt (nur die
 * Vorstellungsliste); ist es ein neues Objekt (neu zusammengesetzt), wird die Auswahl damit neu belegt wie nach "Nicht
 * speichern"; ist es weg, ist die Auswahl leer und die Fußzeile sagt warum.
 *
 * <p>
 * Während die Listen nachgeführt werden, melden {@link #updatingEvents()} und {@link #updatingPresentations()}
 * {@code true}: Die Auswahl-Listener der Shell dürfen dann nichts an den Inhalt weitergeben. Nur im JavaFX-Thread.
 */
public final class EventSelectionSync {

	private final StoreEventRepository repository;
	private final ComboBox<Event> eventBox;
	private final ComboBox<Presentation> presentationBox;
	private final Predicate<Event> listed;
	private final Supplier<Event> shownEvent;
	private final Supplier<Presentation> shownPresentation;
	private final Consumer<Event> applyEventSelection;
	private final Consumer<Presentation> applyPresentation;

	private boolean updatingEvents = false;
	private boolean updatingPresentations = false;

	/**
	 * @param listed              welche Events die Box zeigt (z. B. ohne archivierte)
	 * @param shownEvent          das Event, das die Shell gerade zeigt
	 * @param shownPresentation   die Vorstellung, die die Shell gerade zeigt ({@code null}: alle)
	 * @param applyEventSelection belegt Shell und Inhalt mit einem Event (oder {@code null}) neu; die Vorstellung steht danach auf "alle"
	 * @param applyPresentation   belegt Shell und Inhalt mit einer anderen Vorstellung (oder {@code null}) neu, weil die bisherige
	 *                            weg oder ein neues Objekt ist
	 */
	public EventSelectionSync(StoreEventRepository repository, ComboBox<Event> eventBox, ComboBox<Presentation> presentationBox, Predicate<Event> listed,
			Supplier<Event> shownEvent, Supplier<Presentation> shownPresentation, Consumer<Event> applyEventSelection, Consumer<Presentation> applyPresentation) {
		this.repository = repository;
		this.eventBox = eventBox;
		this.presentationBox = presentationBox;
		this.listed = listed;
		this.shownEvent = shownEvent;
		this.shownPresentation = shownPresentation;
		this.applyEventSelection = applyEventSelection;
		this.applyPresentation = applyPresentation;
	}

	/** {@code true}, solange die Event-Box nachgeführt wird. */
	public boolean updatingEvents() {
		return updatingEvents;
	}

	/** {@code true}, solange die Vorstellungs-Box nachgeführt wird. */
	public boolean updatingPresentations() {
		return updatingPresentations;
	}

	/** Führt beide Boxen nach dem Stand des Repositorys nach. */
	public void sync() {
		Event shown = shownEvent.get();
		Presentation shownPres = shownPresentation.get();
		List<Event> all = repository.loadEvents();
		List<Event> events = all.stream().filter(listed).toList();
		Event current = shown == null ? null : events.stream().filter(event -> event.equals(shown)).findFirst().orElse(null);

		// Die Auswahl wird unten gezielt nachgeführt; erst abmelden und neu wählen lässt die Box den (ggf. neuen) Namen zeigen
		updatingEvents = true;
		try {
			eventBox.getItems().setAll(events);
			eventBox.getSelectionModel().clearSelection();
			if (current != null) {
				eventBox.getSelectionModel().select(current);
			}
		} finally {
			updatingEvents = false;
		}

		if (shown == null) {
			return;
		}
		if (current == null) {
			boolean archived = all.stream().anyMatch(event -> event.equals(shown));
			applyEventSelection.accept(null);
			clearValues();
			StatusMessage.getInstance().show(Severity.WARNING,
					"„" + EntityLabels.nameOrPlaceholder(shown.getName()) + "“ wurde auf einem anderen Rechner " + (archived ? "archiviert." : "gelöscht."));
		} else if (current != shown) {
			applyEventSelection.accept(current);
			String presentationId = shownPres == null ? null : shownPres.getId();
			presentationBox.getItems().stream().filter(presentation -> presentation != null && presentation.getId().equals(presentationId)).findFirst()
					.ifPresent(presentation -> presentationBox.getSelectionModel().select(presentation));
		} else {
			syncPresentations(current, shownPres);
		}
	}

	/** Eine ComboBox behält ihren Wert auch ohne Eintrag in der Liste und zeigte sonst weiter das gelöschte Event. */
	private void clearValues() {
		updatingEvents = true;
		updatingPresentations = true;
		try {
			eventBox.setValue(null);
			presentationBox.setValue(null);
		} finally {
			updatingEvents = false;
			updatingPresentations = false;
		}
	}

	/** Das gewählte Event ist dasselbe Objekt: Vorstellungen können dazugekommen, weg oder umbenannt sein. */
	private void syncPresentations(Event event, Presentation shown) {
		List<Presentation> items = new ArrayList<>();
		items.add(null); // "alle"
		items.addAll(event.getPresentations());
		Presentation kept = shown == null ? null : items.stream().filter(presentation -> presentation != null && presentation.equals(shown)).findFirst().orElse(null);
		updatingPresentations = true;
		try {
			presentationBox.getItems().setAll(items);
			presentationBox.getSelectionModel().clearSelection();
			presentationBox.getSelectionModel().select(items.indexOf(kept));
		} finally {
			updatingPresentations = false;
		}
		// Weg oder ein neues Objekt mit derselben ID: der Inhalt zeigt sonst eine Vorstellung, die nicht mehr im Event steht
		if (kept != shown) {
			applyPresentation.accept(kept);
		}
	}
}
