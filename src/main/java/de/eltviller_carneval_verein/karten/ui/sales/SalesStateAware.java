package de.eltviller_carneval_verein.karten.ui.sales;

/** Ein Inhalt der Verkaufsshell, der Auswahl und Ausschnitt in den gemeinsamen {@link SalesViewState} meldet. */
public interface SalesStateAware {

	/** Wird nach dem Laden der Ansicht aufgerufen, vor {@code setEvent} und {@code setPresentation}. */
	void setSalesState(SalesViewState state);

	/**
	 * Wird zuletzt aufgerufen, wenn die Shell zwischen Tabelle und Saalansicht wechselt: Die Auswahl der alten Ansicht
	 * bleibt erhalten. Die Ansicht wählt davon aus, was sie zeigt (die Saalansicht nur Buchungen ihrer Vorstellung).
	 *
	 * @param bookings die Auswahl der verlassenen Ansicht
	 */
	void restoreSelection(java.util.Collection<de.eltviller_carneval_verein.karten.model.Booking> bookings);
}
