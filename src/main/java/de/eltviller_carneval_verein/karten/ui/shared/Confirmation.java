package de.eltviller_carneval_verein.karten.ui.shared;

/** Eine Rückfrage mit eigenem Knopf; in der App {@code ConfirmDialog.confirm} (KARV-131). */
@FunctionalInterface
public interface Confirmation {

	/** {@code true}, wenn der Nutzer {@code knopf} wählt. */
	boolean ask(String titel, String text, String knopf);

	/**
	 * Wie {@link #ask(String, String, String)}, mit eigenem Text für den Abbruchknopf (KARV-132: „Später“); ohne eigene
	 * Umsetzung bleibt es beim üblichen Abbruchknopf.
	 *
	 * @param abbrechen der Text des Abbruchknopfs, {@code null} für den üblichen
	 */
	default boolean ask(String titel, String text, String knopf, String abbrechen) {
		return ask(titel, text, knopf);
	}
}
