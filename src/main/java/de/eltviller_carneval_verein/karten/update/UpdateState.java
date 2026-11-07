package de.eltviller_carneval_verein.karten.update;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** Der Stand der Update-Prüfung (KARV-131). */
public sealed interface UpdateState {

	/** Noch keine Prüfung gelaufen. */
	record NotChecked() implements UpdateState {
	}

	/** Eine Prüfung läuft; {@code vorher} ist der Stand davor und nie selbst {@code Checking}. */
	record Checking(UpdateState vorher) implements UpdateState {
		public Checking {
			vorher = vorher.effective();
		}
	}

	/** Die installierte Version ist die neueste. */
	record UpToDate(Instant geprueft) implements UpdateState {
	}

	/** Eine neuere Version ist verfügbar. */
	record Available(UpdateInfo neu, Instant geprueft) implements UpdateState {
	}

	/** Die letzte Prüfung scheiterte; eine vorher bekannte neuere Version bleibt bekannt. */
	record CheckFailed(Grund grund, Instant zeitpunkt, Optional<Available> zuletztBekannt) implements UpdateState {
	}

	/** Nicht installiert (Entwicklung) oder der Aufbau der Prüfung scheiterte. */
	record Unsupported() implements UpdateState {
	}

	/** Gründe, aus denen eine Prüfung auch beim nächsten Mal scheitern wird. */
	Set<Grund> SYSTEMATIC = EnumSet.of(Grund.CERTIFICATE, Grund.PROXY, Grund.SERVER, Grund.INVALID, Grund.UNKNOWN);

	/** Der Zustand ohne laufende Prüfung: bei {@code Checking} der Stand davor. */
	default UpdateState effective() {
		return this instanceof Checking c ? c.vorher() : this;
	}

	/** Die bekannte neuere Version, auch während einer Prüfung und nach einer gescheiterten. */
	default Optional<Available> knownUpdate() {
		UpdateState e = effective();
		if (e instanceof Available a) {
			return Optional.of(a);
		}
		if (e instanceof CheckFailed f) {
			return f.zuletztBekannt();
		}
		return Optional.empty();
	}

	/** Ob die Prüfung systematisch scheitert und keine neuere Version bekannt ist (Hinweis „Update-Prüfung nicht möglich“). */
	default boolean checkImpossible() {
		return knownUpdate().isEmpty() && effective() instanceof CheckFailed f && SYSTEMATIC.contains(f.grund());
	}
}
