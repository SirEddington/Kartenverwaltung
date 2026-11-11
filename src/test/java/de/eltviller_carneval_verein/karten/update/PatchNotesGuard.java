package de.eltviller_carneval_verein.karten.update;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Der Vergleich des Wächter-Tests (KARV-125), als reine Funktion für eigene Tests: Die oberste Version der Patchnotes,
 * die pom-Version und {@code app.version} in {@code conveyor.conf} müssen als Text gleich und dreiteilig sein. Text,
 * weil Tag, Fenstertitel und Link den Text nutzen; dreiteilig, weil KARV-132 die Version eines Ordner-Angebots immer
 * als {@code a.b.c} bildet und sonst ins Leere verlinkt.
 */
final class PatchNotesGuard {

	private static final Pattern THREE_PARTS = Pattern.compile("\\d+\\.\\d+\\.\\d+");

	private PatchNotesGuard() {
	}

	/** Je Abweichung ein Satz; leer, wenn alles passt. */
	static List<String> mismatches(String notesTop, String pomVersion, String conveyorVersion) {
		List<String> problems = new ArrayList<>();
		if (!THREE_PARTS.matcher(pomVersion).matches()) {
			problems.add("Die Version " + pomVersion + " ist nicht dreiteilig (a.b.c).");
		}
		if (!notesTop.equals(pomVersion)) {
			problems.add("Oben in patchnotes.md steht " + notesTop + ", die pom.xml baut " + pomVersion
					+ ": Abschnitt für die neue Version fehlt oder ist anders geschrieben.");
		}
		if (!conveyorVersion.equals(pomVersion)) {
			problems.add("conveyor.conf hat app.version " + conveyorVersion + ", die pom.xml " + pomVersion + ".");
		}
		return problems;
	}
}
