package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Stellt sicher, dass Events und Hallen nur über {@link EventSaver} und {@link HallSaver} gespeichert werden:
 * Außerhalb der Saver und der Repositories darf nirgends direkt {@code saveEvent}/{@code saveHall} aufgerufen
 * werden. Sonst gäbe es wieder Speicherwege, die an einer künftigen Prüfung oder Sicherung vorbeilaufen.
 */
class SaveRoutingTest {

	private static final Path SOURCE_ROOT = Path.of("src", "main", "java");
	private static final Pattern DIRECT_SAVE = Pattern.compile("(\\.|::)save(Event|Hall)\\w*\\b");

	@Test
	void noDirectRepositorySavesOutsideSavers() throws IOException {
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
				if (isAllowed(file)) {
					continue;
				}
				List<String> lines = Files.readAllLines(file);
				for (int i = 0; i < lines.size(); i++) {
					if (DIRECT_SAVE.matcher(lines.get(i)).find()) {
						offenders.add(SOURCE_ROOT.relativize(file) + ":" + (i + 1) + "  " + lines.get(i).trim());
					}
				}
			}
		}
		assertTrue(offenders.isEmpty(), "Direkte Speicheraufrufe außerhalb der Saver: " + offenders);
	}

	private static boolean isAllowed(Path file) {
		String name = file.getFileName().toString();
		return file.getParent().getFileName().toString().equals("repository") || name.equals("EventSaver.java") || name.equals("HallSaver.java");
	}
}
