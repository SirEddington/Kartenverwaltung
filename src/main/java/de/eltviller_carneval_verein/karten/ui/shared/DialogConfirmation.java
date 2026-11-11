package de.eltviller_carneval_verein.karten.ui.shared;

import java.net.URI;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.stage.Window;

/**
 * Die Rückfrage der App über einen Dialog (in der App {@code ConfirmDialog::confirm}, KARV-125). Eine eigene Klasse
 * statt eines Lambdas, damit alle drei {@code ask}-Wege getestet sind: Fehlte die Fünf-Argument-Methode, verwürfe die
 * Standardmethode den Link still.
 */
public final class DialogConfirmation implements Confirmation {

	private static final String CANCEL = "Abbrechen";
	private static final String ACTION_STYLE = "button-primary";

	/** Die Signatur von {@code ConfirmDialog.confirm} mit Abbruchtext und Link; {@code linkText null} = ohne Link. */
	@FunctionalInterface
	public interface Dialog {
		boolean confirm(String title, String message, String actionLabel, String actionStyleClass, String cancelLabel,
				Window owner, String linkText, URI page, BiConsumer<URI, Consumer<String>> open);
	}

	private final Dialog dialog;
	private final Window owner;

	/** @param owner das Fenster, über dem die Rückfrage erscheint; {@code null} für das Standardverhalten */
	public DialogConfirmation(Dialog dialog, Window owner) {
		this.dialog = dialog;
		this.owner = owner;
	}

	@Override
	public boolean ask(String titel, String text, String knopf) {
		return ask(titel, text, knopf, null, Optional.empty());
	}

	@Override
	public boolean ask(String titel, String text, String knopf, String abbrechen) {
		return ask(titel, text, knopf, abbrechen, Optional.empty());
	}

	@Override
	public boolean ask(String titel, String text, String knopf, String abbrechen, Optional<Link> link) {
		return dialog.confirm(titel, text, knopf, ACTION_STYLE, abbrechen == null ? CANCEL : abbrechen, owner,
				link.map(Link::text).orElse(null), link.map(Link::page).orElse(null), link.map(Link::open).orElse(null));
	}
}
