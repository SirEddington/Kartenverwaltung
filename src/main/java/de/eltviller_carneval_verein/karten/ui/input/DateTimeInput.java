package de.eltviller_carneval_verein.karten.ui.input;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.TemporalQuery;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Liest und schreibt Datum und Uhrzeit für die Eingabe (ohne JavaFX, mit JUnit testbar). Gelesen wird <strong>streng</strong>:
 * Der Formatter {@code dd.MM.yyyy} im Standardmodus machte aus "31.02.2026" still den 28.02.2026. Eingaben mit und
 * ohne führende Null gehen ("5.3.2027", "7:30"); angezeigt wird immer {@code dd.MM.yyyy} und {@code HH:mm}.
 */
public final class DateTimeInput {

	private static final DateTimeFormatter DATE_IN = DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT);
	private static final DateTimeFormatter TIME_IN = DateTimeFormatter.ofPattern("H:mm").withResolverStyle(ResolverStyle.STRICT);
	/** Uhrzeit ohne Doppelpunkt: "930" oder "1500". */
	private static final Pattern COMPACT_TIME = Pattern.compile("\\d{3,4}");
	private static final DateTimeFormatter DATE_OUT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
	private static final DateTimeFormatter TIME_OUT = DateTimeFormatter.ofPattern("HH:mm");

	private DateTimeInput() {
	}

	/** Liest ein Datum; leer, wenn der Text leer oder kein gültiges Datum ist (auch bei ungültigem Tag wie 31.02.). */
	public static Optional<LocalDate> parseDate(String text) {
		return parse(text, DATE_IN, LocalDate::from);
	}

	/**
	 * Liest eine Uhrzeit (Stunde und Minute); leer, wenn der Text leer oder keine gültige Uhrzeit ist. Auch drei oder
	 * vier Ziffern ohne Doppelpunkt gehen: "1500" ist 15:00, "930" ist 09:30.
	 */
	public static Optional<LocalTime> parseTime(String text) {
		String trimmed = text == null ? null : text.trim();
		if (trimmed != null && COMPACT_TIME.matcher(trimmed).matches()) {
			int minutes = trimmed.length() - 2;
			trimmed = trimmed.substring(0, minutes) + ":" + trimmed.substring(minutes);
		}
		return parse(trimmed, TIME_IN, LocalTime::from);
	}

	private static <T> Optional<T> parse(String text, DateTimeFormatter formatter, TemporalQuery<T> query) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(formatter.parse(text.trim(), query));
		} catch (DateTimeParseException e) {
			return Optional.empty();
		}
	}

	public static String formatDate(LocalDate date) {
		return DATE_OUT.format(date);
	}

	public static String formatTime(LocalTime time) {
		return TIME_OUT.format(time);
	}
}
