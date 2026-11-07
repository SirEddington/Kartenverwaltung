package de.eltviller_carneval_verein.karten.model;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ein Aufzählungswert, der beim Lesen auch einen unbekannten Namen übersteht (z. B. eine Form oder Ausrichtung, die erst
 * eine neuere Programmversion kennt). Statt zu scheitern, gilt dann ein Rückfallwert; den Originaltext merkt sich das Feld
 * und schreibt ihn unverändert zurück, solange niemand den Wert ändert. Bekannte Werte werden genau mit ihrem Namen
 * geschrieben, damit ältere Programmversionen sie lesen.
 *
 * @param <E> die Aufzählung
 */
public final class TolerantEnum<E extends Enum<E>> {

	private static final Logger LOGGER = Logger.getLogger(TolerantEnum.class.getName());

	private final Class<E> type;
	private final E fallback;
	private E value;
	private String original;

	/**
	 * @param type     die Aufzählung
	 * @param fallback der Wert für einen unbekannten Namen
	 * @param initial  der Anfangswert
	 */
	public TolerantEnum(Class<E> type, E fallback, E initial) {
		this.type = type;
		this.fallback = fallback;
		this.value = initial;
	}

	public E get() {
		return value;
	}

	/** Setzt den Wert; der gemerkte Originaltext gilt nur weiter, wenn der Wert derselbe bleibt. */
	public void set(E value) {
		if (value != this.value) {
			original = null;
		}
		this.value = value;
	}

	/** Liest den Namen aus der Datei: bekannt gilt er, {@code null} bleibt {@code null}, sonst gilt der Rückfallwert. */
	public void fromJson(String text) {
		if (text == null) {
			value = null;
			original = null;
			return;
		}
		for (E constant : type.getEnumConstants()) {
			if (constant.name().equals(text)) {
				value = constant;
				original = null;
				return;
			}
		}
		LOGGER.log(Level.FINE, "Unbekannter Wert \"{0}\" für {1}, es gilt {2}",
				new Object[] { text, type.getSimpleName(), fallback });
		value = fallback;
		original = text;
	}

	/** Der Text für die Datei: der gemerkte Originaltext, sonst der Name des Werts ({@code null} ohne Wert). */
	public String toJson() {
		if (original != null) {
			return original;
		}
		return value != null ? value.name() : null;
	}

	/** Übernimmt Wert und Originaltext. */
	public void copyFrom(TolerantEnum<E> source) {
		value = source.value;
		original = source.original;
	}
}
