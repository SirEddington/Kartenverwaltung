package de.eltviller_carneval_verein.karten.ui.input;

import java.util.Optional;
import java.util.function.Supplier;

import javafx.util.StringConverter;

/**
 * Umhüllt einen Converter, der bei unlesbarem Text eine Exception wirft: {@link #fromString} wirft nie, sondern
 * merkt sich den Fehler und gibt einen Ersatzwert zurück (den bisherigen Wert der Zelle oder des Spinners). Wer den
 * Converter benutzt, fragt danach mit {@link #takeFailure()}, ob die Eingabe gelesen werden konnte. Der Merker hängt
 * am Converter und nicht am Auslöser (Enter oder Fokusverlust), deshalb ist er unabhängig davon, wie JavaFX das
 * Bestätigen anstößt.
 *
 * @param <T> Typ des gelesenen Werts
 */
public final class ParsingConverter<T> extends StringConverter<T> {

	/** Ein nicht lesbarer Text und die Fehlerart, die dazu gehört. */
	public record Failure(InputIssue issue, String text) {
	}

	private final StringConverter<T> delegate;
	private final InputIssue issueOnFailure;
	private final Supplier<T> fallback;
	private Failure failure;

	/**
	 * @param delegate       der eigentliche Converter, darf bei unlesbarem Text eine {@link RuntimeException} werfen
	 * @param issueOnFailure Fehlerart, die bei einer Exception gemerkt wird
	 * @param fallback       liefert den Wert, der bei einem Fehler zurückgegeben wird
	 */
	public ParsingConverter(StringConverter<T> delegate, InputIssue issueOnFailure, Supplier<T> fallback) {
		this.delegate = delegate;
		this.issueOnFailure = issueOnFailure;
		this.fallback = fallback;
	}

	@Override
	public String toString(T value) {
		return delegate.toString(value);
	}

	@Override
	public T fromString(String text) {
		try {
			T value = delegate.fromString(text);
			failure = null;
			return value;
		} catch (RuntimeException e) {
			failure = new Failure(issueOnFailure, text == null ? "" : text);
			return fallback.get();
		}
	}

	/** Der Fehler des letzten {@link #fromString}-Aufrufs, falls es einen gab; ein zweiter Aufruf liefert nichts. */
	public Optional<Failure> takeFailure() {
		Optional<Failure> taken = Optional.ofNullable(failure);
		failure = null;
		return taken;
	}
}
