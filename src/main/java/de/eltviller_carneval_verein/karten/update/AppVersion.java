package de.eltviller_carneval_verein.karten.update;

import java.util.Optional;
import java.util.function.Function;

/**
 * Eine Programmversion wie Conveyor sie setzt: {@code app.version} (1 bis 4 Zahlen, durch Punkte getrennt) und
 * {@code app.revision} (eine Zahl ≥ 0). Verglichen wird Feld für Feld numerisch, fehlende Felder zählen als 0, danach die
 * Revision (KARV-131).
 *
 * @param version  die Version wie gelesen, z. B. {@code 1.1.0}
 * @param revision die Revision, 0 bei echten Releases
 */
public record AppVersion(String version, int revision) implements Comparable<AppVersion> {

	/**
	 * Liest eine Version; {@code revision} {@code null} oder leer zählt als 0.
	 *
	 * @return leer, wenn eins der beiden nicht passt (auch bei einem Zahlenüberlauf)
	 */
	public static Optional<AppVersion> parse(String version, String revision) {
		if (version == null || fields(version) == null) {
			return Optional.empty();
		}
		int rev = 0;
		if (revision != null && !revision.isEmpty()) {
			Integer parsed = number(revision);
			if (parsed == null) {
				return Optional.empty();
			}
			rev = parsed;
		}
		return Optional.of(new AppVersion(version, rev));
	}

	/** Die Version aus {@code app.version} und {@code app.revision}. */
	public static Optional<AppVersion> from(Function<String, String> props) {
		return parse(props.apply("app.version"), props.apply("app.revision"));
	}

	/** Die Version dieser laufenden App, leer außerhalb eines Pakets (Eclipse, {@code javafx:run}). */
	public static Optional<AppVersion> current() {
		return from(System::getProperty);
	}

	/**
	 * Dieselbe Versionsnummer mit Revision 0: für Vergleiche, bei denen nur die Versionsnummer zählt (KARV-125, etwa
	 * Patchnotes und Release-Seite; das Tag auf GitHub hat keine Revision).
	 */
	public AppVersion withoutRevision() {
		return revision == 0 ? this : new AppVersion(version, 0);
	}

	/** {@code 1.1.0}, bei einer Revision über 0 {@code 1.1.0 (Rev. 1)}. */
	public String display() {
		return revision > 0 ? version + " (Rev. " + revision + ")" : version;
	}

	@Override
	public int compareTo(AppVersion other) {
		int[] a = fields(version);
		int[] b = fields(other.version);
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			int x = i < a.length ? a[i] : 0;
			int y = i < b.length ? b[i] : 0;
			if (x != y) {
				return Integer.compare(x, y);
			}
		}
		return Integer.compare(revision, other.revision);
	}

	/** Die Zahlen der Version, {@code null} bei falscher Form. */
	private static int[] fields(String version) {
		String[] parts = version.split("\\.", -1);
		if (parts.length < 1 || parts.length > 4) {
			return null;
		}
		int[] result = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			Integer n = number(parts[i]);
			if (n == null) {
				return null;
			}
			result[i] = n;
		}
		return result;
	}

	private static Integer number(String text) {
		if (text.isEmpty() || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
			return null;
		}
		try {
			return Integer.parseInt(text);
		} catch (NumberFormatException overflow) {
			return null;
		}
	}
}
