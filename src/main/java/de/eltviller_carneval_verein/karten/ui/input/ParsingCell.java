package de.eltviller_carneval_verein.karten.ui.input;

/**
 * Eine Textzelle, die {@link TableInput} selbst bearbeiten lässt (Tippen, Enter, Tab aus dem Bearbeiten). Andere Zellen
 * (Auswahllisten, Checkboxen) behalten ihr eigenes Verhalten.
 */
public interface ParsingCell {

	/** Ob die Zeile der Zelle sich jetzt bearbeiten lässt; Tabelle und Spalte prüft JavaFX selbst. */
	boolean canEdit();
}
