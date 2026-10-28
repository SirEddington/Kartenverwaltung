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
 * Eine Liste wird nur neu gesetzt, wenn sie sich wirklich unterscheidet (andere Objekte oder ein anderer angezeigter Name),
 * und die Auswahl nur, wenn sie nicht schon stimmt: Fremde Änderungen kommen alle paar Sekunden, eine offene Liste soll
 * dabei weder flackern noch zugehen.
 *
 * <p>
 * Während die Listen nachgeführt werden, melden {@link #updatingEvents()} und {@link #updatingPresentations()}
 * {@code true}: Die Auswahl-Listener der Shell dürfen dann nichts an den Inhalt weitergeben. Nur im JavaFX-Thread.
 */
public final class EventSelectionSync {

	/**
	 * Was der Abgleich von einer Auswahlbox braucht; in der App eine {@link ComboBox} ({@link #of}). Paketsichtbar, damit
	 * Tests ohne Oberfläche auskommen.
	 */
	interface Box<T> {

		/** Die Einträge der Liste. */
		List<T> items();

		/** Ersetzt die Einträge; wie bei der ComboBox ist danach nichts gewählt. */
		void setItems(List<T> items);

		/** Die gewählte Zeile, {@code -1} ohne Auswahl. */
		int selectedIndex();

		/** Leert die Auswahl und wählt die Zeile ({@code -1}: keine). */
		void select(int index);

		/** Der Text, den die Box für den Eintrag zeigt. */
		String label(T item);

		/** Leert den Wert der Box: Eine ComboBox behält ihn sonst auch ohne Eintrag in der Liste. */
		void clearValue();
	}

	/** Die ComboBox als {@link Box}. */
	static <T> Box<T> of(ComboBox<T> box) {
		return new Box<>() {
			@Override
			public List<T> items() {
				return box.getItems();
			}

			@Override
			public void setItems(List<T> items) {
				box.getItems().setAll(items);
			}

			@Override
			public int selectedIndex() {
				return box.getSelectionModel().getSelectedIndex();
			}

			@Override
			public void select(int index) {
				box.getSelectionModel().clearSelection();
				if (index >= 0) {
					box.getSelectionModel().select(index);
				}
			}

			@Override
			public String label(T item) {
				return box.getConverter().toString(item);
			}

			@Override
			public void clearValue() {
				box.setValue(null);
			}
		};
	}

	/** Was eine Box nach dem letzten Setzen durch diese Klasse zeigt: die Einträge und ihre Texte von damals. */
	private record Shown<T>(List<T> items, List<String> labels) {
	}

	private final StoreEventRepository repository;
	private final Box<Event> eventBox;
	private final Box<Presentation> presentationBox;
	private final Predicate<Event> listed;
	private final Supplier<Event> shownEvent;
	private final Supplier<Presentation> shownPresentation;
	private final Consumer<Event> applyEventSelection;
	private final Consumer<Presentation> applyPresentation;
	private final Consumer<String> warning;

	private boolean updatingEvents = false;
	private boolean updatingPresentations = false;
	private Shown<Event> shownEvents;
	private Shown<Presentation> shownPresentations;

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
		this(repository, of(eventBox), of(presentationBox), listed, shownEvent, shownPresentation, applyEventSelection, applyPresentation,
				text -> StatusMessage.getInstance().show(Severity.WARNING, text));
	}

	/** Wie oben, mit beliebigen Boxen und einem eigenen Weg für den Hinweis in der Fußzeile (für Tests). */
	EventSelectionSync(StoreEventRepository repository, Box<Event> eventBox, Box<Presentation> presentationBox, Predicate<Event> listed,
			Supplier<Event> shownEvent, Supplier<Presentation> shownPresentation, Consumer<Event> applyEventSelection, Consumer<Presentation> applyPresentation,
			Consumer<String> warning) {
		this.repository = repository;
		this.eventBox = eventBox;
		this.presentationBox = presentationBox;
		this.listed = listed;
		this.shownEvent = shownEvent;
		this.shownPresentation = shownPresentation;
		this.applyEventSelection = applyEventSelection;
		this.applyPresentation = applyPresentation;
		this.warning = warning;
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

		updatingEvents = true;
		try {
			// Neu gesetzt und neu gewählt zeigt die Box auch einen geänderten Namen
			shownEvents = show(eventBox, events, current, shownEvents);
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
			warning.accept("„" + EntityLabels.nameOrPlaceholder(shown.getName()) + "“ wurde auf einem anderen Rechner " + (archived ? "archiviert." : "gelöscht."));
		} else if (current != shown) {
			applyEventSelection.accept(current);
			String presentationId = shownPres == null ? null : shownPres.getId();
			List<Presentation> presentations = presentationBox.items();
			for (int index = 0; index < presentations.size(); index++) {
				Presentation presentation = presentations.get(index);
				if (presentation != null && presentation.getId().equals(presentationId)) {
					presentationBox.select(index);
					break;
				}
			}
		} else {
			syncPresentations(current, shownPres);
		}
	}

	/**
	 * Setzt die Einträge nur, wenn sie sich von dem unterscheiden, was die Box zeigt, und wählt {@code selected}, wenn es nicht
	 * schon gewählt ist.
	 *
	 * @return was die Box danach zeigt
	 */
	private static <T> Shown<T> show(Box<T> box, List<T> items, T selected, Shown<T> before) {
		List<String> labels = items.stream().map(box::label).toList();
		int index = indexOf(items, selected);
		if (before == null || !sameObjects(box.items(), items) || !sameObjects(before.items(), items) || !before.labels().equals(labels)) {
			box.setItems(items);
			box.select(index);
		} else if (box.selectedIndex() != index) {
			box.select(index);
		}
		return new Shown<>(new ArrayList<>(items), labels);
	}

	/** Die Stelle des Objekts in der Liste (nach Identität), {@code -1}, wenn es fehlt. */
	private static <T> int indexOf(List<T> items, T item) {
		for (int index = 0; index < items.size(); index++) {
			if (items.get(index) == item) {
				return index;
			}
		}
		return -1;
	}

	private static <T> boolean sameObjects(List<T> a, List<T> b) {
		if (a.size() != b.size()) {
			return false;
		}
		for (int index = 0; index < a.size(); index++) {
			if (a.get(index) != b.get(index)) {
				return false;
			}
		}
		return true;
	}

	/** Eine ComboBox behält ihren Wert auch ohne Eintrag in der Liste und zeigte sonst weiter das gelöschte Event. */
	private void clearValues() {
		updatingEvents = true;
		updatingPresentations = true;
		try {
			eventBox.clearValue();
			presentationBox.clearValue();
			shownEvents = null;
			shownPresentations = null;
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
			shownPresentations = show(presentationBox, items, kept, shownPresentations);
		} finally {
			updatingPresentations = false;
		}
		// Weg oder ein neues Objekt mit derselben ID: der Inhalt zeigt sonst eine Vorstellung, die nicht mehr im Event steht
		if (kept != shown) {
			applyPresentation.accept(kept);
		}
	}
}
