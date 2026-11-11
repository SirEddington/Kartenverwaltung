package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Wächter (KARV-125): Die Patchnotes in der App sind gültig, und ihre oberste Version ist die, die gebaut wird – als
 * Text gleich in {@code patchnotes.md}, {@code pom.xml} und {@code conveyor.conf} (Conveyor macht daraus das Tag). So
 * kann kein Release ohne passende Patchnotes entstehen. Liest die Dateien relativ zum Projektordner wie
 * {@code ArchitectureGuardTest}.
 */
class PatchNotesBundledTest {

	private static final Pattern PROJECT_VERSION = Pattern.compile("<version>([^<]+)</version>");
	private static final Pattern CONVEYOR_VERSION = Pattern.compile("^  version = \"(.*)\"$");

	@Test
	void bundledMatchesPomAndConveyor() throws IOException {
		String top = PatchNotes.bundled().releases().get(0).version().version();
		assertEquals(List.of(), PatchNotesGuard.mismatches(top, pomVersion(), conveyorVersion()));
	}

	/** Das erste {@code <version>} direkt unter {@code <project>}, also nach {@code </parent>} und vor allen Abhängigkeiten. */
	private static String pomVersion() throws IOException {
		Path pom = Path.of("pom.xml");
		assertTrue(Files.exists(pom), "pom.xml nicht gefunden unter " + pom.toAbsolutePath());
		String text = Files.readString(pom, StandardCharsets.UTF_8);
		int parentEnd = text.indexOf("</parent>");
		int from = parentEnd >= 0 ? parentEnd : text.indexOf("<project");
		int to = firstOf(text, from, "<dependencies>", "<build>", "<properties>", "<dependencyManagement>");
		Matcher m = PROJECT_VERSION.matcher(text.substring(from, to));
		assertTrue(m.find(), "keine Projektversion in pom.xml");
		return m.group(1).strip();
	}

	private static int firstOf(String text, int from, String... marks) {
		int best = text.length();
		for (String mark : marks) {
			int i = text.indexOf(mark, from);
			if (i >= 0 && i < best) {
				best = i;
			}
		}
		return best;
	}

	private static String conveyorVersion() throws IOException {
		Path conveyor = Path.of("conveyor.conf");
		assertTrue(Files.exists(conveyor), "conveyor.conf nicht gefunden unter " + conveyor.toAbsolutePath());
		for (String line : Files.readAllLines(conveyor, StandardCharsets.UTF_8)) {
			Matcher m = CONVEYOR_VERSION.matcher(line.replace("\r", ""));
			if (m.matches()) {
				return m.group(1);
			}
		}
		throw new AssertionError("keine Zeile '  version = \"…\"' in conveyor.conf");
	}
}
