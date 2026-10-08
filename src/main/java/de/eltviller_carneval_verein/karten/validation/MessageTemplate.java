package de.eltviller_carneval_verein.karten.validation;

import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Setzt Werte in eine Meldungsvorlage mit Platzhaltern {@code &1} bis {@code &9} ein (wie in SAP).
 *
 * <ul>
 * <li>{@code &n} wird durch den n-ten Parameter ersetzt (gezählt ab 1).</li>
 * <li>Fehlt der Parameter, bleibt {@code &n} im Text stehen, es wird nie eine Ausnahme geworfen:
 * ein Fehler in einer Meldung darf die Prüfung nicht abbrechen.</li>
 * <li>Überzählige Parameter werden ignoriert.</li>
 * <li>{@code &&} ergibt ein einzelnes {@code &}; ein {@code &} ohne folgende Ziffer 1-9 bleibt unverändert.</li>
 * </ul>
 */
public final class MessageTemplate {

	private MessageTemplate() {
	}

	/** Setzt die Parameter in die Vorlage ein. */
	public static String format(String template, Object... params) {
		StringBuilder result = new StringBuilder(template.length() + 16);
		for (int i = 0; i < template.length(); i++) {
			char c = template.charAt(i);
			if (c != '&' || i + 1 >= template.length()) {
				result.append(c);
				continue;
			}
			char next = template.charAt(i + 1);
			if (next == '&') {
				result.append('&');
				i++;
			} else if (next >= '1' && next <= '9') {
				int index = next - '1';
				result.append(params != null && index < params.length ? String.valueOf(params[index]) : "&" + next);
				i++;
			} else {
				result.append(c);
			}
		}
		return result.toString();
	}

	/** Die in der Vorlage vorkommenden Platzhalter-Nummern (für Tests: lückenlos 1..n). */
	static SortedSet<Integer> placeholders(String template) {
		SortedSet<Integer> numbers = new TreeSet<>();
		for (int i = 0; i + 1 < template.length(); i++) {
			if (template.charAt(i) != '&') {
				continue;
			}
			char next = template.charAt(i + 1);
			if (next == '&') {
				i++;
			} else if (next >= '1' && next <= '9') {
				numbers.add(next - '0');
				i++;
			}
		}
		return numbers;
	}
}
