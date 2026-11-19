package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Der Name dieses Computers als Vorschlag für den Rechnernamen ({@link MachineIdentity}, KARV-195). Gefragt wird der
 * Reihe nach: {@code COMPUTERNAME} (Windows), {@code /etc/hostname} (Linux), {@code HOSTNAME} (unter Linux nur gesetzt,
 * wenn die Shell es exportiert, in Programmen vom Desktop meist nicht). Die erste Quelle mit einem Namen gilt
 * ("localhost" zählt nicht als einer); liefert keine einen, ist das Ergebnis {@code null}. Bewusst nicht über die Namensauflösung
 * ({@code InetAddress.getLocalHost()}): Sie kann beim Start Sekunden hängen. Ohne JavaFX.
 */
final class ComputerName {

	private static final Path ETC_HOSTNAME = Path.of("/etc/hostname");

	private ComputerName() {
	}

	/** Der Name dieses Computers; {@code null}, wenn keine Quelle einen liefert. */
	static String system() {
		return find(System::getenv, () -> firstLine(ETC_HOSTNAME));
	}

	/**
	 * @param environment Umgebungsvariablen nach Namen ({@code null}, wenn nicht gesetzt)
	 * @param etcHostname liefert den Namen aus {@code /etc/hostname} oder {@code null}; nur gefragt, wenn
	 *                    {@code COMPUTERNAME} nichts liefert
	 */
	static String find(Function<String, String> environment, Supplier<String> etcHostname) {
		String name = text(environment.apply("COMPUTERNAME"));
		if (name != null) {
			return name;
		}
		name = text(etcHostname.get());
		return name != null ? name : text(environment.apply("HOSTNAME"));
	}

	/**
	 * Die erste Zeile von {@code file} mit Text, die kein Kommentar ({@code #}) ist, wie {@code hostname(5)} sie liest;
	 * {@code null}, wenn es keine gibt oder die Datei fehlt oder unlesbar ist.
	 */
	static String firstLine(Path file) {
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String stripped = line.strip();
				if (!stripped.isEmpty() && !stripped.startsWith("#")) {
					return stripped;
				}
			}
		} catch (IOException e) {
			// fehlt (Windows), ist unlesbar oder kein UTF-8: dann eben die nächste Quelle
		}
		return null;
	}

	/**
	 * Der Wert, wenn er einen Rechner benennt; {@code null} für nichts, Leerraum und "localhost" (auch
	 * {@code localhost.localdomain}), den Namen, den Linux ohne eingerichteten Namen trägt und der wie "Rechner" alle
	 * gleich hieße. Leerraum am Rand entfernt {@link MachineIdentity}.
	 */
	private static String text(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String name = value.strip().toLowerCase(Locale.ROOT);
		return name.equals("localhost") || name.startsWith("localhost.") ? null : value;
	}
}
