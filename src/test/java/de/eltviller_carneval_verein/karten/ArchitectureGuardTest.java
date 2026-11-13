package de.eltviller_carneval_verein.karten;

import static de.eltviller_carneval_verein.karten.model.FormatGuardTest.RULES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Architektur-Wache für das Datenformat (KARV-136): Ein Payload darf nur über {@code UnitMapper.readUnit} ins Modell
 * gelesen werden, das gesperrte Inhalte herausnimmt. Ein neuer Codeweg mit {@code treeToValue}/{@code readValue} auf eine
 * Modellklasse könnte sonst gesperrte Inhalte zeigen. Der Test durchsucht den Quelltext unter {@code src/main/java}.
 */
class ArchitectureGuardTest {

	private static final Path SOURCES = Path.of("src/main/java/de/eltviller_carneval_verein/karten");

	private static final Pattern CALL = Pattern.compile("\\b(treeToValue|readValue|convertValue)\\s*\\(");
	private static final Pattern CLASS_LITERAL = Pattern.compile("\\b(\\w+)\\.class\\b");
	/** Eine Methode oder ein Konstruktor einer Klasse oberster Ebene (genau ein Tab eingerückt). */
	private static final Pattern DECLARATION = Pattern.compile("^\\t(?![\\t @])[\\w<>\\[\\],.? ]*?\\b(\\w+)\\s*\\(");

	/**
	 * Die bekannten Stellen (Datei#Methode) mit der Zahl ihrer Aufrufe, die eine Modellklasse lesen oder deren Typ hier
	 * nicht zu sehen ist (generisches {@code type}). Aufrufe mit einer Klasse außerhalb des Modells (Einstellungen) sind
	 * immer erlaubt.
	 */
	private static final Map<String, Integer> KNOWN = Map.of(
			// die Regel selbst: readUnit prüft die Sperre und ruft parse
			"repository/UnitMapper.java#parse", 1,
			// Konflikt-Dialog: eine frühere Fassung wieder schreiben; der Inhalt wird vor dem Speichern vom Gate geprüft
			"repository/StoreEventRepository.java#readBooking", 1,
			"repository/StoreHallRepository.java#readHall", 1,
			// Felder aus dem Konflikt-Dialog in den eigenen Stand mischen; ein gesperrter Inhalt scheitert beim Speichern am Gate
			"repository/StoreEventRepository.java#applyFields", 3,
			// liest die eigenen Bytes eines Events, das über readUnit zusammengesetzt wurde
			"repository/StoreEventRepository.java#rebuild", 1,
			// gemerkte Zustände des eigenen Modells (Rückgängig, Einarbeiten, Validierung)
			"tracking/StateRestorer.java#restore", 1,
			"ui/LiveUpdates.java#rebase", 1,
			"ui/LiveValidator.java#issuesBefore", 1);

	@Test
	void modelIsReadFromPayloadsOnlyInKnownPlaces() throws IOException {
		assertTrue(Files.isDirectory(SOURCES), "Quelltext nicht gefunden: " + SOURCES.toAbsolutePath() + " – Regeln: " + RULES);
		Set<String> modelClasses = javaFiles(SOURCES.resolve("model")).stream()
				.map(file -> file.getFileName().toString().replace(".java", "")).collect(Collectors.toSet());

		Map<String, Integer> found = new TreeMap<>();
		for (Path file : javaFiles(SOURCES)) {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			String method = null;
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				Matcher declaration = DECLARATION.matcher(line);
				if (declaration.find() && !line.contains("=")) {
					method = declaration.group(1);
				}
				if (isComment(line)) {
					continue;
				}
				Matcher call = CALL.matcher(line);
				while (call.find()) {
					String target = statementFrom(lines, i, call.start());
					Matcher literal = CLASS_LITERAL.matcher(target);
					if (literal.find() && !modelClasses.contains(literal.group(1))) {
						continue; // keine Modellklasse, etwa die Einstellungen
					}
					String site = SOURCES.relativize(file).toString().replace('\\', '/') + "#" + method;
					if (method == null) {
						fail("Keine Methode gefunden für " + site + ":" + (i + 1) + " – Regeln: " + RULES);
					}
					found.merge(site, 1, Integer::sum);
				}
			}
		}
		assertEquals(new TreeMap<>(KNOWN), found, "treeToValue/readValue auf eine Modellklasse an einer neuen Stelle (oder eine bekannte Stelle"
				+ " hat sich geändert): Payloads nur über UnitMapper.readUnit ins Modell lesen, sonst kommen gesperrte Inhalte ins Modell"
				+ " – Regeln: " + RULES);
	}

	private static List<Path> javaFiles(Path folder) throws IOException {
		try (Stream<Path> files = Files.walk(folder)) {
			return files.filter(file -> file.toString().endsWith(".java")).sorted().toList();
		}
	}

	private static boolean isComment(String line) {
		String trimmed = line.trim();
		return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
	}

	/** Der Aufruf ab {@code start} bis zum Ende der Anweisung (höchstens fünf Zeilen). */
	private static String statementFrom(List<String> lines, int index, int start) {
		StringBuilder statement = new StringBuilder(lines.get(index).substring(start));
		for (int i = index + 1; i < Math.min(lines.size(), index + 5) && statement.indexOf(";") < 0; i++) {
			statement.append(' ').append(lines.get(i));
		}
		int end = statement.indexOf(";");
		return end < 0 ? statement.toString() : statement.substring(0, end);
	}
}
