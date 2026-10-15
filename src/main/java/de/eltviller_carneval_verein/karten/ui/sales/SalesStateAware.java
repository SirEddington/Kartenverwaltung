package de.eltviller_carneval_verein.karten.ui.sales;

/** Ein Inhalt der Verkaufsshell, der Auswahl und Ausschnitt in den gemeinsamen {@link SalesViewState} meldet. */
public interface SalesStateAware {

	/** Wird nach dem Laden der Ansicht aufgerufen, vor {@code setEvent} und {@code setPresentation}. */
	void setSalesState(SalesViewState state);
}
