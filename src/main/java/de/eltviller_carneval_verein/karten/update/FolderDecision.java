package de.eltviller_carneval_verein.karten.update;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Wägt einen Installer im gemeinsamen Ordner gegen die letzte erfolgreiche Prüfung der Update-Quelle ab (KARV-132, Spec
 * „Abgleich mit der Quelle“). Eine reine Funktion; den Merker der Nachprüfung hält der Aufrufer.
 */
public final class FolderDecision {

	/** Was mit dem Kandidaten geschieht. */
	public enum Result {
		/** Update aus dem Ordner anbieten. */
		FOLDER,
		/** Kein Angebot aus dem Ordner; das Update von GitHub gilt wie bisher. */
		GITHUB,
		/** Neuer als die Quelle nach einer jüngeren Prüfung: vermutlich zurückgezogen, nicht anbieten. */
		WITHDRAWN,
		/** Neuer als die Quelle, die Prüfung ist aber älter als die Datei: einmal nachprüfen, bis dahin nichts. */
		UNCLEAR,
		/** Kein Kandidat. */
		NONE
	}

	/** So lange wartet „unklar“ höchstens auf das Ergebnis der Nachprüfung. */
	public static final Duration RECHECK_WAIT = Duration.ofMinutes(5);

	private FolderDecision() {
	}

	/**
	 * @param recheckStartedAt wann für diesen Kandidaten die Nachprüfung angestoßen wurde; leer, wenn noch nicht
	 */
	public static Result decide(Optional<FolderOffer> candidate, Optional<UpdateSuccess> lastSuccess, UpdateState current,
			Optional<Installation> inst, Optional<Instant> recheckStartedAt, Instant now) {
		if (candidate.isEmpty()) {
			return Result.NONE;
		}
		if (lastSuccess.isEmpty()) {
			return Result.FOLDER;
		}
		FolderOffer c = candidate.get();
		UpdateSuccess s = lastSuccess.get();
		int cmp = c.version().compareTo(s.info().version());
		if (cmp == 0) {
			return Result.FOLDER;
		}
		if (cmp < 0) {
			boolean githubUsable = !(current.effective() instanceof UpdateState.CheckFailed)
					&& inst.map(i -> i.canUpdateInPlace(s.info())).orElse(false);
			return githubUsable ? Result.GITHUB : Result.FOLDER;
		}
		if (s.geprueft().isAfter(c.firstSeen())) {
			return Result.WITHDRAWN;
		}
		if (recheckStartedAt.isEmpty()) {
			return Result.UNCLEAR;
		}
		Instant t = recheckStartedAt.get();
		if (current.effective() instanceof UpdateState.CheckFailed f && !f.zeitpunkt().isBefore(t)) {
			return Result.FOLDER;
		}
		if (!now.isBefore(t.plus(RECHECK_WAIT))) {
			return Result.FOLDER;
		}
		return Result.UNCLEAR;
	}
}
