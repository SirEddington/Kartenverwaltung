package de.eltviller_carneval_verein.karten.ui;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker.Snapshot;
import de.eltviller_carneval_verein.karten.validation.EventValidator;
import de.eltviller_carneval_verein.karten.validation.ValidationIssue;
import de.eltviller_carneval_verein.karten.validation.ValidationResult;

/**
 * Verzögerte Live-Prüfung (Stufe 2 des Validierungskonzepts): Sobald der {@link ChangeTracker} nach 300 ms Ruhe
 * eine Änderung an einem Event meldet, läuft {@link EventValidator#validate(Event)} über das ganze Event. Es gibt
 * keinen zweiten Auslöser neben dem des Trackers.
 *
 * <p>
 * Die Fußzeile ändert sich nur, wenn sich die <strong>Menge der Beanstandungen</strong> gegenüber dem Zustand
 * davor geändert hat: bestehende Meldungen werden nicht ständig wiederholt, und Altlasten, die schon beim Laden da
 * waren (z. B. alte Dateien ohne Datum), melden sich erst beim Speichern. Neue Beanstandungen erscheinen, nicht
 * mehr vorhandene verschwinden.
 *
 * <p>
 * Das Speichern bleibt unabhängig davon durch den {@link EventSaver} geschützt; die Live-Prüfung ist nur Komfort.
 */
public final class LiveValidator implements ChangeTracker.Listener<Event> {

	private static final Logger LOG = Logger.getLogger(LiveValidator.class.getName());

	/** Wohin das Ergebnis geht (die Fußzeile; in Tests eine Mitschrift). */
	interface Sink {
		/** Die Menge der Beanstandungen hat sich geändert und ist nicht leer. */
		void show(ValidationResult result);

		/** Die Menge der Beanstandungen hat sich geändert und ist jetzt leer. */
		void clear();
	}

	/** Die Beanstandungen eines Zustands, erkannt an dessen Hash. */
	private record Evaluated(String hash, Set<ValidationIssue> issues) {
	}

	private final Sink sink;
	private final ObjectMapper mapper = JsonMapperFactory.create();
	// Je Event der zuletzt geprüfte Zustand: Er ist beim nächsten Mal der "Zustand davor" und muss nicht neu gelesen werden
	private final Map<String, Evaluated> evaluated = new HashMap<>();

	LiveValidator(Sink sink) {
		this.sink = sink;
	}

	/** Meldet die Live-Prüfung beim Tracker der Events an; das Ergebnis geht in die Fußzeile. */
	public static void install() {
		ChangeTracker.events().addListener(new LiveValidator(new Sink() {
			@Override
			public void show(ValidationResult result) {
				StatusMessage.getInstance().showLive(result);
			}

			@Override
			public void clear() {
				StatusMessage.getInstance().clearIssues();
			}
		}));
	}

	@Override
	public void onChanged(Event event, Snapshot before, Snapshot after) {
		Set<ValidationIssue> previous = issuesBefore(event, before);

		ValidationResult result = EventValidator.validate(event);
		Set<ValidationIssue> current = new LinkedHashSet<>(result.getIssues());
		evaluated.put(event.getId(), new Evaluated(after.hash(), current));

		if (current.equals(previous)) {
			return;
		}
		if (current.isEmpty()) {
			sink.clear();
		} else {
			sink.show(result);
		}
	}

	/** Die Beanstandungen im Zustand davor: aus dem Merkzettel, sonst aus den Bytes des Snapshots gelesen. */
	private Set<ValidationIssue> issuesBefore(Event event, Snapshot before) {
		Evaluated known = evaluated.get(event.getId());
		if (known != null && known.hash().equals(before.hash())) {
			return known.issues();
		}
		try {
			Event earlier = mapper.readValue(before.bytes(), Event.class);
			return new HashSet<>(EventValidator.validate(earlier).getIssues());
		} catch (IOException e) {
			// Ohne den früheren Zustand gilt "es gab nichts": im Zweifel lieber eine Meldung zu viel
			LOG.log(Level.WARNING, "Früherer Zustand des Events konnte nicht gelesen werden: " + event, e);
			return Set.of();
		}
	}
}
