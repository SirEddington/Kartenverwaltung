package de.eltviller_carneval_verein.karten.ui.shared;

/** Eine Rückfrage mit eigenem Knopf; in der App {@code ConfirmDialog.confirm} (KARV-131). */
@FunctionalInterface
public interface Confirmation {

	/** {@code true}, wenn der Nutzer {@code knopf} wählt. */
	boolean ask(String titel, String text, String knopf);
}
