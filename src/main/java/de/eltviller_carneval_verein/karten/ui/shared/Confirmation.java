package de.eltviller_carneval_verein.karten.ui.shared;

import java.net.URI;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Eine Rückfrage mit eigenem Knopf; in der App {@code ConfirmDialog.confirm} (KARV-131). */
@FunctionalInterface
public interface Confirmation {

	/**
	 * Ein Link unter dem Text der Rückfrage (KARV-125: „Was ist neu in Version …?“).
	 *
	 * @param text der Linktext
	 * @param page die Adresse, auch als Tooltip
	 * @param open öffnet die Adresse; meldet einen Fehler mit dem Text für den Nutzer an den zweiten Parameter
	 */
	record Link(String text, URI page, BiConsumer<URI, Consumer<String>> open) {
	}

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

	/**
	 * Wie {@link #ask(String, String, String, String)}, dazu ein Link unter dem Text (KARV-125); ohne eigene Umsetzung
	 * entfällt der Link.
	 *
	 * @param abbrechen der Text des Abbruchknopfs, {@code null} für den üblichen
	 * @param link      der Link, leer für keinen
	 */
	default boolean ask(String titel, String text, String knopf, String abbrechen, Optional<Link> link) {
		return abbrechen == null ? ask(titel, text, knopf) : ask(titel, text, knopf, abbrechen);
	}
}
