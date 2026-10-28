package de.eltviller_carneval_verein.karten.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.Deferral;
import de.eltviller_carneval_verein.karten.repository.ForeignBatch;
import de.eltviller_carneval_verein.karten.repository.ForeignResult;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;
import de.eltviller_carneval_verein.karten.repository.RepositoryListener;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.store.SyncListener;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.store.VersionId;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker.Snapshot;
import de.eltviller_carneval_verein.karten.tracking.StateRestorer;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.application.Platform;

/**
 * Die Koordination fremder Änderungen in der App (Spec KARV-85): Der {@link SyncListener} des Versionsspeichers gibt die
 * gemeldeten Einheiten an die Repositories weiter, die sie in Cache und Vergleichsstand einarbeiten, und führt dabei die
 * Änderungserkennung nach, damit ein fremder Stand nie als ungespeicherte Änderung des Nutzers gilt und "Nicht
 * speichern" ihn nicht wieder entfernt. Außerdem versucht sie zurückgestellte Stände erneut und meldet sie der Fußzeile.
 *
 * <p>
 * Reihenfolge einer Meldung: erst die Hallen; für jede neue oder ersetzte Halle werden die Events, die der Tracker
 * kennt und die Vorstellungen in dieser Halle haben, gegen die neue Bestuhlung abgeglichen ({@link BookingSyncService});
 * dann die Events. Nicht verfolgte Events gleicht die Ansicht beim Öffnen ab, wie bei jedem Laden.
 *
 * <p>
 * Der Tracker wird so nachgeführt, wie die Repositories ihre Rückrufe geben: Vor dem Einarbeiten wird die Entität
 * verglichen ({@code changingByOthers}), damit eine gerade getippte Eingabe als Änderung des Nutzers erkannt wird. Danach
 * ({@code changedByOthers}) wird neu aufgesetzt, ohne die Listener des Trackers zu benachrichtigen: War die Entität
 * sauber, ist ihr neuer Stand der gespeicherte. Hatte der Nutzer etwas ungespeichert geändert, wird der gespeicherte Stand
 * auf einer Kopie des bisherigen gespeicherten Stands mit denselben fremden Einheiten fortgeschrieben; so bleibt
 * "ungespeichert" genau, was der Nutzer geändert hat, und "Nicht speichern" stellt einen Stand mit dem fremden
 * Verkauf her. Nicht verfolgte und nie gespeicherte Entitäten bleiben, wie sie sind.
 *
 * <p>
 * Das erneute Versuchen zurückgestellter Stände (nach dem Speichern, nach "Nicht speichern" und am Ende einer
 * Bearbeitung) läuft über {@code later}, nie im Aufruf selbst: Der Tracker muss das Speichern bzw. Wiederherstellen
 * zuerst verbucht haben. In der App ist {@code later} der FX-Thread ({@code Platform::runLater}), die Reihenfolge der
 * Anmeldungen spielt dann keine Rolle. Läuft {@code later} sofort (Tests), muss {@code LiveUpdates} <em>nach</em> dem
 * Aktualisierer des Trackers an den Repositories angemeldet sein, damit dessen {@code saved} zuerst läuft.
 *
 * <p>
 * Alles auf dem FX-Thread. Die Registrierung als Listener geschieht im Konstruktor und wird nie zurückgenommen: Eine
 * Instanz lebt so lange wie die Anwendung.
 */
public final class LiveUpdates {

	private static final Logger LOG = Logger.getLogger(LiveUpdates.class.getName());

	/** Für das gespeicherte Nachspielen auf einer Kopie; derselbe Mapper wie der des Trackers. */
	private static final ObjectMapper MAPPER = JsonMapperFactory.create();

	/** Welche Version einer Einheit der Nutzer schon als zurückgestellt gemeldet bekommen hat. */
	private record Announced(UnitKey unit, VersionId version) {
	}

	private final StoreHallRepository halls;
	private final StoreEventRepository events;
	private final ChangeTracker<Hall> hallTracker;
	private final ChangeTracker<Event> eventTracker;
	private final Executor later;
	private final Consumer<String> hint;

	// Hallen, die seit dem letzten Abgleich der Events neu oder ersetzt sind
	private final Set<String> changedHallIds = new LinkedHashSet<>();
	private final Set<Announced> announced = new HashSet<>();
	private final EventListener eventListener = new EventListener();
	private boolean retryScheduled;

	/**
	 * Verbindet die Repositories mit den Trackern und der Registratur der Bearbeitungen.
	 *
	 * @param editing welche Objekte gerade bearbeitet werden; ihr Ende löst ein erneutes Einarbeiten aus
	 * @param later   führt das erneute Einarbeiten aus, nachdem der laufende Aufruf zu Ende ist (in der App {@code Platform::runLater})
	 * @param hint    zeigt einen Hinweis in der Fußzeile
	 */
	public LiveUpdates(StoreHallRepository halls, StoreEventRepository events, ChangeTracker<Hall> hallTracker,
			ChangeTracker<Event> eventTracker, EditingUnits editing, Executor later, Consumer<String> hint) {
		this.halls = halls;
		this.events = events;
		this.hallTracker = hallTracker;
		this.eventTracker = eventTracker;
		this.later = later;
		this.hint = hint;

		// Eine Halle ist gesperrt, solange sie bearbeitet wird oder Buchungen in ihren Vorstellungen ungespeichert sind oder
		// bearbeitet werden (der Abgleich mit der neuen Bestuhlung nähme sonst einen Platzhalter weg, in den gerade getippt wird)
		halls.setBlockedCheck(hall -> editing.isEditing(hall) || isEditingIn(hall, editing) || hasUnsavedBookings(hall));
		events.setEditingCheck(editing::isEditing);
		events.setHallLookup(halls::findById);
		halls.addListener(new HallListener());
		events.addListener(eventListener);
		editing.addEndListener(this::scheduleRetry);
	}

	/**
	 * Der Listener am Event-Repository (paketsichtbar für Tests: Ein Schub, dessen Nachspielen scheitert, entsteht nur im
	 * Test, siehe {@code TestRepositories.failingBatch}).
	 */
	RepositoryListener<Event> eventListener() {
		return eventListener;
	}

	/**
	 * Die Rückmeldungen des Versionsspeichers kommen auf dem FX-Thread an. Gezeigt wird, was der Nutzer wissen muss und nicht
	 * selbst sieht: ein Fehler des Dienstes und eine eigene Änderung, die gegen eine andere verloren hat; dazu arbeitet
	 * {@code unitsChanged} fremde Stände ein.
	 */
	public SyncListener listener() {
		return new SyncListener() {
			@Override
			public void unitsChanged(Set<UnitKey> units) {
				apply(() -> halls.applyForeign(units), () -> events.applyForeign(units));
			}

			@Override
			public void error(String message) {
				StatusMessage.getInstance().show(Severity.ERROR, message);
			}

			@Override
			public void lost(UnitKey unit, List<VersionId> mine, VersionId winner) {
				StatusMessage.getInstance().show(Severity.ERROR, "Eine Änderung an " + unit.relativePath()
						+ " wurde nicht übernommen, weil ein anderer Stand zuerst gespeichert war. Bitte die Daten prüfen.");
			}
		};
	}

	/** Nach "Nicht speichern" wird zurückgestellten Ständen eine neue Gelegenheit gegeben (über {@code later}). */
	public void watch(StateRestorer<Event> eventRestorer, StateRestorer<Hall> hallRestorer) {
		eventRestorer.addListener((replaced, restored) -> scheduleRetry());
		hallRestorer.addListener((replaced, restored) -> scheduleRetry());
	}

	/** Wie die Anwendung sie braucht: die Repositories und Tracker der App, Wiederholen und Hinweise auf dem FX-Thread. */
	public static void install(SyncService sync) {
		LiveUpdates live = new LiveUpdates(StoreHallRepository.getInstance(), StoreEventRepository.getInstance(),
				ChangeTracker.halls(), ChangeTracker.events(), EditingUnits.getInstance(), Platform::runLater,
				text -> StatusMessage.getInstance().show(Severity.WARNING, text));
		live.watch(StateRestorer.events(), StateRestorer.halls());
		sync.setListener(live.listener());
	}

	// Einarbeiten -->

	private void scheduleRetry() {
		if (retryScheduled) {
			return;
		}
		retryScheduled = true;
		try {
			later.execute(() -> {
				retryScheduled = false;
				apply(halls::retryDeferred, events::retryDeferred);
			});
		} catch (RuntimeException e) {
			retryScheduled = false;
			throw e;
		}
	}

	/**
	 * Hallen, dann Abgleich der Events mit neuen Hallen, dann Events; zuletzt die Hinweise: erst die Warnungen, dann die
	 * zurückgestellten Stände. Die Fußzeile zeigt nur den letzten; der Hinweis "deine Änderung ist noch nicht gespeichert"
	 * kommt nur einmal je Version und darf nicht von einer Warnung desselben Schubs verdeckt werden. Ein Fehler in einem
	 * Schritt hält die anderen nicht auf.
	 */
	private void apply(Supplier<ForeignResult> hallStep, Supplier<ForeignResult> eventStep) {
		List<Deferral> deferrals = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		collect(hallStep, deferrals, warnings);
		prepareEventsOfChangedHalls();
		collect(eventStep, deferrals, warnings);
		// erst jetzt: Ein Hinweis kann einen Dialog öffnen, in dem weitere Meldungen ankommen
		warnings.forEach(this::show);
		for (Deferral deferral : deferrals) {
			if (announced.add(new Announced(deferral.unit(), deferral.version()))) {
				show(deferralText(deferral));
			}
		}
	}

	private void collect(Supplier<ForeignResult> step, List<Deferral> deferrals, List<String> warnings) {
		try {
			ForeignResult result = step.get();
			deferrals.addAll(result.deferred());
			warnings.addAll(result.warnings());
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "Fremde Änderungen ließen sich nicht einarbeiten", e);
			warnings.add("Änderungen von einem anderen Rechner ließen sich nicht einarbeiten.");
		}
	}

	private void show(String text) {
		try {
			hint.accept(text);
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "Hinweis konnte nicht gezeigt werden: " + text, e);
		}
	}

	/**
	 * Gleicht die verfolgten Events mit Vorstellungen in einer neuen oder ersetzten Halle gegen deren Bestuhlung ab. Der
	 * Abgleich ist keine Änderung des Nutzers ({@link BookingSyncService#prepare}).
	 */
	private void prepareEventsOfChangedHalls() {
		if (changedHallIds.isEmpty()) {
			return;
		}
		Set<String> hallIds = new LinkedHashSet<>(changedHallIds);
		changedHallIds.clear();
		for (Event event : events.loadEvents()) {
			if (eventTracker.baseline(event) == null || !usesAny(event, hallIds)) {
				continue;
			}
			try {
				BookingSyncService.prepare(event, halls::findById, eventTracker);
			} catch (RuntimeException e) {
				LOG.log(Level.SEVERE, "Buchungen ließen sich nicht mit der neuen Halle abgleichen: " + event, e);
			}
		}
	}

	private static boolean usesAny(Event event, Set<String> hallIds) {
		return presentationsOf(event).stream().anyMatch(p -> hallIds.contains(p.getHallId()));
	}

	private static List<Presentation> presentationsOf(Event event) {
		return event.getPresentations() != null ? event.getPresentations() : List.of();
	}

	/** {@code true}, wenn in einer Vorstellung dieser Halle eine Buchung ungespeichert geändert ist. */
	private boolean hasUnsavedBookings(Hall hall) {
		Set<UnitKey> changed = events.locallyChangedUnits();
		if (changed.isEmpty()) {
			return false;
		}
		Set<String> presentationIds = new HashSet<>();
		presentationsIn(hall).forEach(presentation -> presentationIds.add(presentation.getId()));
		return changed.stream().anyMatch(unit -> unit.type() == UnitType.BOOKING && presentationIds.contains(unit.presentationId()));
	}

	/** {@code true}, wenn eine Vorstellung in dieser Halle oder eine ihrer Buchungen gerade bearbeitet wird. */
	private boolean isEditingIn(Hall hall, EditingUnits editing) {
		for (Presentation presentation : presentationsIn(hall)) {
			if (editing.isEditing(presentation)) {
				return true;
			}
			List<Booking> bookings = presentation.getBookings() != null ? presentation.getBookings() : List.of();
			if (bookings.stream().anyMatch(editing::isEditing)) {
				return true;
			}
		}
		return false;
	}

	/** Alle Vorstellungen aller Events, die in dieser Halle stattfinden. */
	private List<Presentation> presentationsIn(Hall hall) {
		List<Presentation> result = new ArrayList<>();
		for (Event event : events.loadEvents()) {
			for (Presentation presentation : presentationsOf(event)) {
				if (hall.getId().equals(presentation.getHallId())) {
					result.add(presentation);
				}
			}
		}
		return result;
	}

	// <-- Einarbeiten

	// Hinweise -->

	private static String deferralText(Deferral deferral) {
		String machine = deferral.machineName() == null ? "einem anderen Rechner" : "Rechner " + deferral.machineName();
		return "„" + label(deferral.model()) + "“ wurde auf " + machine + " geändert; deine Änderung ist noch nicht gespeichert.";
	}

	/** Der Eintrag, den der Hinweis nennt: Buchung über {@link EntityLabels#booking}, sonst der Name. */
	private static String label(Object model) {
		return switch (model) {
		case Booking booking -> EntityLabels.booking(booking);
		case Event event -> EntityLabels.nameOrPlaceholder(event.getName());
		case Presentation presentation -> EntityLabels.nameOrPlaceholder(presentation.getName());
		case Hall hall -> EntityLabels.nameOrPlaceholder(hall.getName());
		case null, default -> EntityLabels.NAME_PLACEHOLDER;
		};
	}

	// <-- Hinweise

	// Änderungserkennung -->

	/** Die Hallen: Ein ersetztes Objekt ist immer sauber (sonst wäre der fremde Stand zurückgestellt). */
	private final class HallListener implements RepositoryListener<Hall> {

		@Override
		public void saved(Hall hall) {
			scheduleRetry();
		}

		@Override
		public void changingByOthers(Hall hall) {
			hallTracker.check(hall);
		}

		@Override
		public void changedByOthers(Hall hall, ForeignBatch<Hall> batch) {
			changedHallIds.add(hall.getId());
			if (hallTracker.baseline(hall) != null && !hallTracker.isNew(hall)) {
				hallTracker.rebaseToCurrent(hall);
			}
		}

		@Override
		public void addedByOthers(Hall hall) {
			changedHallIds.add(hall.getId());
		}

		@Override
		public void removedByOthers(Hall hall) {
			hallTracker.untrack(hall);
		}
	}

	private final class EventListener implements RepositoryListener<Event> {

		@Override
		public void saved(Event event) {
			scheduleRetry();
		}

		@Override
		public void changingByOthers(Event event) {
			// eine gerade getippte, noch nicht verglichene Änderung des Nutzers vor dem Einarbeiten erkennen
			eventTracker.check(event);
		}

		@Override
		public void changedByOthers(Event event, ForeignBatch<Event> batch) {
			rebase(event, batch);
		}

		@Override
		public void removedByOthers(Event event) {
			eventTracker.untrack(event);
		}
	}

	/**
	 * Führt den Tracker nach dem Einarbeiten nach. Nach {@code changingByOthers} sagt {@code isDirty}, ob der Nutzer vor dem
	 * Einarbeiten etwas ungespeichert geändert hatte.
	 */
	private void rebase(Event event, ForeignBatch<Event> batch) {
		Snapshot baseline = eventTracker.baseline(event);
		if (baseline == null || eventTracker.isNew(event)) {
			return;
		}
		if (!eventTracker.isDirty(event)) {
			eventTracker.rebaseToCurrent(event);
			return;
		}
		try {
			Event copy = MAPPER.readValue(baseline.bytes(), Event.class);
			batch.replayOn(copy);
			eventTracker.rebase(event, eventTracker.snapshotOf(copy));
		} catch (IOException | RuntimeException e) {
			// Der gespeicherte Stand bleibt der alte: Das Event gilt weiter als geändert, nichts geht still verloren.
			// "Nicht speichern" würde aber den fremden Stand aus der Ansicht nehmen; darauf weist der Hinweis hin.
			LOG.log(Level.SEVERE, "Der gespeicherte Stand ließ sich nicht fortschreiben: " + event, e);
			show("Änderungen von einem anderen Rechner an „" + EntityLabels.nameOrPlaceholder(event.getName())
					+ "“ konnten nicht nachgeführt werden. Bitte speichern statt verwerfen.");
		}
	}

	// <-- Änderungserkennung
}
