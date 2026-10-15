package de.eltviller_carneval_verein.karten.ui;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker.Snapshot;
import de.eltviller_carneval_verein.karten.validation.EventValidator;
import de.eltviller_carneval_verein.karten.validation.HallValidator;
import de.eltviller_carneval_verein.karten.validation.ValidationIssue;
import de.eltviller_carneval_verein.karten.validation.ValidationResult;

/**
 * Verzögerte Live-Prüfung (Stufe 2 des Validierungskonzepts): Sobald der {@link ChangeTracker} nach 300 ms Ruhe
 * eine Änderung an einem Event oder einer Halle meldet, läuft {@link EventValidator#validate(Event)} über das
 * ganze Event bzw. {@link HallValidator#validate(Hall)} über die ganze Halle. Es gibt keinen zweiten Auslöser
 * neben dem des Trackers.
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
public final class LiveValidator<T> implements ChangeTracker.Listener<T> {

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

	private final Class<T> type;
	private final Function<T, String> idOf;
	private final Function<T, ValidationResult> validator;
	// Der ObjectMapper ist nach der Konfiguration threadsicher und wird von allen Instanzen geteilt
	private static final ObjectMapper MAPPER = JsonMapperFactory.create();

	private final Sink sink;
	// Je Entität der zuletzt geprüfte Zustand: Er ist beim nächsten Mal der "Zustand davor" und muss nicht neu gelesen werden
	private final Map<String, Evaluated> evaluated = new HashMap<>();

	/**
	 * @param type      Art der Entität ({@link Event} oder {@link Hall}), zum Lesen des früheren Zustands
	 * @param idOf      ID der Entität
	 * @param validator die Prüfung über die ganze Entität
	 * @param sink      wohin das Ergebnis geht
	 */
	LiveValidator(Class<T> type, Function<T, String> idOf, Function<T, ValidationResult> validator, Sink sink) {
		this.type = type;
		this.idOf = idOf;
		this.validator = validator;
		this.sink = sink;
	}

	/** Meldet die Live-Prüfung bei den Trackern der Events und der Hallen an; das Ergebnis geht in die Fußzeile. */
	public static void install() {
		Sink footer = footerSink();
		ChangeTracker.events().addListener(new LiveValidator<>(Event.class, Event::getId, EventValidator::validate, footer));
		ChangeTracker.halls().addListener(new LiveValidator<>(Hall.class, Hall::getId, HallValidator::validate, footer));
	}

	private static Sink footerSink() {
		return new Sink() {
			@Override
			public void show(ValidationResult result) {
				StatusMessage.getInstance().showLive(result);
			}

			@Override
			public void clear() {
				StatusMessage.getInstance().clearIssues();
			}
		};
	}

	@Override
	public void onChanged(T entity, Snapshot before, Snapshot after) {
		Set<ValidationIssue> previous = issuesBefore(entity, before);

		ValidationResult result = validator.apply(entity);
		Set<ValidationIssue> current = new LinkedHashSet<>(result.getIssues());
		evaluated.put(idOf.apply(entity), new Evaluated(after.hash(), current));

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
	private Set<ValidationIssue> issuesBefore(T entity, Snapshot before) {
		Evaluated known = evaluated.get(idOf.apply(entity));
		if (known != null && known.hash().equals(before.hash())) {
			return known.issues();
		}
		try {
			T earlier = MAPPER.readValue(before.bytes(), type);
			return new HashSet<>(validator.apply(earlier).getIssues());
		} catch (IOException e) {
			// Ohne den früheren Zustand gilt "es gab nichts": im Zweifel lieber eine Meldung zu viel
			LOG.log(Level.WARNING, "Früherer Zustand konnte nicht gelesen werden: " + entity, e);
			return Set.of();
		}
	}
}
