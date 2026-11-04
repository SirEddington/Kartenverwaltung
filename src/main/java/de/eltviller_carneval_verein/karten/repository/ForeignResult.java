package de.eltviller_carneval_verein.karten.repository;

import java.util.List;

/**
 * Das Ergebnis des Einarbeitens fremder Stände.
 *
 * @param deferred alle Stände, die danach zurückgestellt sind (auch die aus früheren Aufrufen); der Hinweis in der
 *                 Fußzeile kommt einmal je Einheit und Version
 * @param warnings Warnungen für den Nutzer (unlesbare oder gesperrte Einheiten, gescheitertes Einarbeiten)
 */
public record ForeignResult(List<Deferral> deferred, List<String> warnings) {

	/** Macht die Listen unveränderlich. */
	public ForeignResult {
		deferred = List.copyOf(deferred);
		warnings = List.copyOf(warnings);
	}
}
