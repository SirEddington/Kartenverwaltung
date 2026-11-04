package de.eltviller_carneval_verein.karten.ui.shared;

import java.time.Duration;
import java.util.Optional;

import de.eltviller_carneval_verein.karten.store.SyncState;
import de.eltviller_carneval_verein.karten.validation.MessageTemplate;

/**
 * Der eine Hinweis in der Fußzeile zum Abgleich mit dem gemeinsamen Ordner (Spec "Fußzeile: SyncIndicator", Entscheidung 18):
 * die erste zutreffende Zeile der Vorrangtabelle. Rein, ohne JavaFX und ohne Uhr; wie lange der Zustand {@code AUSSTEHEND}
 * schon dauert, kommt als Dauer von außen.
 *
 * @param rank der Vorrang 1 bis 8 (1 = höchster)
 * @param text die Anzeige
 */
public record SyncHint(int rank, String text) {

	/** Ab dieser Dauer in {@code AUSSTEHEND} zeigt die Fußzeile "Hochladen verzögert". */
	public static final Duration PENDING_AFTER = Duration.ofSeconds(30);

	/**
	 * Der Hinweis zur Lage; leer, wenn die Fußzeile nichts zeigt.
	 * <ol>
	 * <li>{@code ENTSCHEIDUNG}; 2. {@code OFFLINE}; 3. {@code FEHLER};
	 * <li>erster Abgleich unvollständig, und noch kein Lauf zu Ende oder ein Lauf läuft (mit Prozent, sobald der Lauf
	 * begonnen hat);
	 * <li>erster Abgleich unvollständig, kein Lauf, und ein Lauf ist zu Ende gegangen;
	 * <li>{@code AUSSTEHEND} seit mindestens 30 s;
	 * <li>Änderungslisten anderer Rechner warten noch auf das Lesen (Verkäufe anderer fehlen womöglich noch);
	 * <li>übersprungene Einträge.
	 * </ol>
	 * Im Einzelbetrieb ({@code AUS}, auch {@code null}) bleibt die Fußzeile leer, was die Werte sonst sagen.
	 *
	 * @param state         {@code syncState()}
	 * @param pendingFor    wie lange {@code state} schon {@code AUSSTEHEND} ist (sonst ohne Bedeutung); {@code null} gilt als 0
	 * @param complete      {@code connectionComplete()}
	 * @param progress      {@code fullSyncProgress()}, {@code null} ohne laufenden Lauf
	 * @param firstRunEnded ob seit dem Verbinden ein Lauf zu Ende gegangen ist ({@link FirstRunWatch#ended()})
	 * @param catchingUp    {@code catchingUp()}
	 * @param skipped       die Zahl der Einträge in {@code skippedUnits()}
	 */
	public static Optional<SyncHint> of(SyncState state, Duration pendingFor, boolean complete, Double progress,
			boolean firstRunEnded, boolean catchingUp, int skipped) {
		if (state == null || state == SyncState.AUS) {
			return Optional.empty();
		}
		if (state == SyncState.ENTSCHEIDUNG) {
			return hint(1, SharedFolderTexts.HINT_DECISION);
		}
		if (state == SyncState.OFFLINE) {
			return hint(2, SharedFolderTexts.HINT_OFFLINE);
		}
		if (state == SyncState.FEHLER) {
			return hint(3, SharedFolderTexts.HINT_ERROR);
		}
		if (!complete) {
			if (progress == null && firstRunEnded) {
				return hint(5, SharedFolderTexts.HINT_FIRST_RUN_ENDED);
			}
			return hint(4, progress == null ? firstRunWithoutPercent()
					: MessageTemplate.format(SharedFolderTexts.HINT_FIRST_RUN, SharedFolderTexts.percent(progress)));
		}
		if (state == SyncState.AUSSTEHEND && pendingFor != null && pendingFor.compareTo(PENDING_AFTER) >= 0) {
			return hint(6, SharedFolderTexts.HINT_PENDING);
		}
		if (catchingUp) {
			return hint(7, SharedFolderTexts.HINT_CATCHING_UP);
		}
		if (skipped > 0) {
			return hint(8, skipped == 1 ? SharedFolderTexts.HINT_SKIPPED_ONE
					: MessageTemplate.format(SharedFolderTexts.HINT_SKIPPED_MANY, skipped));
		}
		return Optional.empty();
	}

	private static Optional<SyncHint> hint(int rank, String text) {
		return Optional.of(new SyncHint(rank, text));
	}

	/** "Noch nicht vollständig abgeglichen" vor dem Beginn des Laufs: die Vorlage ohne den Prozentteil. */
	private static String firstRunWithoutPercent() {
		String template = SharedFolderTexts.HINT_FIRST_RUN;
		return template.substring(0, template.indexOf(" (&1"));
	}
}
