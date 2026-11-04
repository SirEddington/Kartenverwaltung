package de.eltviller_carneval_verein.karten.store;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Die ID einer Version einer Einheit; zugleich der Dateiname ohne Endung. Aufbau:
 * {@code <Millisekunden>-<Rechner-Kürzel>-<Zähler>} mit
 * <ul>
 * <li>Millisekunden seit 1970 in Basis 36, auf 11 Stellen mit Nullen aufgefüllt,</li>
 * <li>den ersten 8 Zeichen der kleingeschriebenen Rechner-ID,</li>
 * <li>einem Zähler in Basis 36, auf 4 Stellen mit Nullen aufgefüllt.</li>
 * </ul>
 * Das Muster ist {@code [0-9a-z]{11}-[0-9a-z]{8}-[0-9a-z]{4}}. Wegen der festen Breite und der Kleinschreibung
 * ordnet der Textvergleich die IDs nach der Zeit (bei gleicher Millisekunde nach Rechner und Zähler), auf
 * Dateisystemen mit und ohne Unterscheidung der Groß- und Kleinschreibung gleichermaßen.
 *
 * @param value die ID als Text
 */
public record VersionId(String value) implements Comparable<VersionId> {

	private static final Pattern PATTERN = Pattern.compile("[0-9a-z]{11}-[0-9a-z]{8}-[0-9a-z]{4}");

	private static final Pattern MACHINE = Pattern.compile("[0-9a-z]{8}");

	private static final int RADIX = 36;
	private static final int MILLIS_DIGITS = 11;
	private static final int MACHINE_DIGITS = 8;
	private static final int COUNTER_DIGITS = 4;

	/** Kleinster nicht mehr darstellbarer Zähler: 36^4. */
	static final int COUNTER_LIMIT = 36 * 36 * 36 * 36;

	/** Kleinster nicht mehr darstellbarer Zeitwert: 36^11. */
	private static final long MILLIS_LIMIT = 131_621_703_842_267_136L;

	/**
	 * @throws IllegalArgumentException wenn der Text nicht dem Muster entspricht
	 */
	public VersionId {
		if (!isValid(value)) {
			throw new IllegalArgumentException("Keine gültige Versions-ID: " + value);
		}
	}

	/**
	 * Erzeugt die ID aus ihrem Text.
	 *
	 * @throws IllegalArgumentException wenn der Text nicht dem Muster entspricht
	 */
	public static VersionId of(String value) {
		return new VersionId(value);
	}

	/** Ob der Text dem Muster einer Versions-ID entspricht (Kleinbuchstaben und Ziffern, feste Breite). */
	public static boolean isValid(String value) {
		return value != null && PATTERN.matcher(value).matches();
	}

	/**
	 * Baut eine ID aus ihren Teilen.
	 *
	 * @param epochMillis Zeitpunkt in Millisekunden seit 1970 (nicht negativ)
	 * @param machineId   ID des Rechners; die ersten 8 Zeichen, kleingeschrieben, bilden das Kürzel
	 * @param counter     Zähler von 0 bis 36^4 - 1
	 * @throws IllegalArgumentException wenn ein Teil außerhalb des Wertebereichs liegt oder die Rechner-ID kürzer
	 *                                  als 8 Zeichen ist oder im Kürzel andere Zeichen als {@code [0-9a-z]} hat
	 */
	public static VersionId create(long epochMillis, String machineId, int counter) {
		if (epochMillis < 0 || epochMillis >= MILLIS_LIMIT) {
			throw new IllegalArgumentException("Zeitpunkt außerhalb des Wertebereichs: " + epochMillis);
		}
		if (counter < 0 || counter >= COUNTER_LIMIT) {
			throw new IllegalArgumentException("Zähler außerhalb des Wertebereichs: " + counter);
		}
		if (machineId == null || machineId.length() < MACHINE_DIGITS) {
			throw new IllegalArgumentException("Die Rechner-ID muss mindestens " + MACHINE_DIGITS + " Zeichen haben.");
		}
		String machine = machineId.substring(0, MACHINE_DIGITS).toLowerCase(Locale.ROOT);
		if (!MACHINE.matcher(machine).matches()) {
			throw new IllegalArgumentException("Die Rechner-ID beginnt nicht mit 8 Ziffern oder Buchstaben: " + machineId);
		}
		return new VersionId(pad(Long.toString(epochMillis, RADIX), MILLIS_DIGITS) + "-" + machine + "-"
				+ pad(Integer.toString(counter, RADIX), COUNTER_DIGITS));
	}

	private static String pad(String digits, int width) {
		return "0".repeat(width - digits.length()) + digits;
	}

	/** Der Zeitpunkt der ID in Millisekunden seit 1970. */
	public long epochMillis() {
		return Long.parseLong(value.substring(0, MILLIS_DIGITS), RADIX);
	}

	/**
	 * Ob die ID das Kürzel dieses Rechners trägt, also von ihm erzeugt wurde. Das Kürzel sind nur die ersten 8 Zeichen der
	 * Rechner-ID: Zwei verschiedene Rechner mit gleichem Kürzel (bei zufälligen 26-stelligen IDs praktisch ausgeschlossen)
	 * sind hier nicht zu unterscheiden. Für Stellen, die nur die ID einer Version kennen und keine Datei lesen wollen.
	 */
	public boolean isFrom(String machineId) {
		if (machineId == null || machineId.length() < MACHINE_DIGITS) {
			return false;
		}
		int start = MILLIS_DIGITS + 1;
		return value.regionMatches(start, machineId.toLowerCase(Locale.ROOT), 0, MACHINE_DIGITS);
	}

	@Override
	public int compareTo(VersionId other) {
		return value.compareTo(other.value);
	}

	@Override
	public String toString() {
		return value;
	}
}
