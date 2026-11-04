package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectChecksTest {

	private static final Predicate<Path> REACHABLE = path -> true;

	@TempDir
	Path root;

	/** Der eigene Datenordner des Tests: ein vorhandener Unterordner, damit auch "darüber" ein gültiger Ordner existiert. */
	private Path appData() throws IOException {
		return Files.createDirectories(root.resolve("appdata"));
	}

	private Optional<String> check(String text) throws IOException {
		return ConnectChecks.check(text, appData(), REACHABLE);
	}

	@Test
	void emptyPathIsRejected() throws IOException {
		assertEquals(Optional.of("Wähle zuerst einen Ordner."), check(""));
		assertEquals(Optional.of("Wähle zuerst einen Ordner."), check("   "));
		assertEquals(Optional.of("Wähle zuerst einen Ordner."), check(null));
	}

	@Test
	void missingFolderIsRejectedAndNotCreated() throws IOException {
		Path missing = root.resolve("gibt-es-nicht");

		Optional<String> result = check(missing.toString());

		assertEquals(Optional.of("Den Ordner " + missing + " gibt es nicht."
				+ " Wähle einen vorhandenen Ordner; die Kartenverwaltung legt ihn nicht selbst an."), result);
		assertFalse(Files.exists(missing));
	}

	@Test
	void unparsablePathCountsAsMissing() throws IOException {
		Optional<String> result = check("Laufwerk:\u0000\\x");

		assertTrue(result.isPresent());
		assertTrue(result.get().startsWith("Den Ordner "));
	}

	@Test
	void fileIsRejected() throws IOException {
		Path file = Files.createFile(root.resolve("datei.txt"));

		assertEquals(Optional.of(file + " ist kein Ordner."), check(file.toString()));
	}

	@Test
	void unreachableFolderIsRejected() throws IOException {
		Path folder = Files.createDirectories(root.resolve("netz"));
		List<Path> asked = new ArrayList<>();

		Optional<String> result = ConnectChecks.check(folder.toString(), appData(), path -> {
			asked.add(path);
			return false;
		});

		assertEquals(Optional.of("Der Ordner " + folder + " ist nicht erreichbar."
				+ " Prüfe das Netzlaufwerk bzw. ob Nextcloud oder OneDrive läuft, und versuche es erneut."), result);
		assertEquals(List.of(folder), asked);
	}

	@Test
	void missingIsReportedBeforeUnreachable() throws IOException {
		Path missing = root.resolve("gibt-es-nicht");

		Optional<String> result = ConnectChecks.check(missing.toString(), appData(), path -> false);

		assertTrue(result.get().startsWith("Den Ordner "));
	}

	@Test
	void ownDataFolderItselfInsideAndAboveAreRejected() throws IOException {
		Path appData = appData();
		Path inside = Files.createDirectories(appData.resolve("unter").resolve("ordner"));
		String expected = " überschneidet sich mit dem Datenordner dieses Rechners. Wähle einen Ordner außerhalb davon.";

		assertEquals(Optional.of(appData + expected), check(appData.toString()));
		assertEquals(Optional.of(inside + expected), check(inside.toString()));
		assertEquals(Optional.of(root + expected), check(root.toString()));
	}

	@Test
	void siblingWithTheSamePrefixIsNotAnOverlap() throws IOException {
		Path sibling = Files.createDirectories(root.resolve("appdata-gemeinsam"));

		assertEquals(Optional.empty(), check(sibling.toString()));
	}

	@Test
	void overlapIsDetectedNormalizedAndCaseInsensitive() throws IOException {
		Path appData = appData();
		String messageEnd = " überschneidet sich mit dem Datenordner dieses Rechners. Wähle einen Ordner außerhalb davon.";
		// Umweg über "..", dazu Leerzeichen am Rand und ein Trennzeichen am Ende
		String detour = " " + root + "/../" + root.getFileName() + "/" + appData.getFileName() + "/ ";

		Optional<String> result = check(detour);

		assertEquals(Optional.of(detour.trim() + messageEnd), result);

		if (System.getProperty("os.name").startsWith("Windows")) {
			String lower = appData.toString().toLowerCase();
			String upper = appData.toString().toUpperCase();
			assertTrue(check(lower).get().endsWith(messageEnd), "klein geschrieben");
			assertTrue(check(upper).get().endsWith(messageEnd), "groß geschrieben");
		}
	}

	@Test
	void dataSubfolderChosenIsRejected() throws IOException {
		for (String name : List.of("events", "halls", "feeds")) {
			Path chosen = Files.createDirectories(root.resolve("gewaehlt-" + name));
			Files.createDirectories(chosen.resolve(name));

			assertEquals(Optional.of("In " + chosen + " liegen die Daten direkt."
					+ " Wähle den Ordner darüber, also den, der „data“ enthält."), check(chosen.toString()), name);
		}
	}

	@Test
	void validFolderPasses() throws IOException {
		Path shared = Files.createDirectories(root.resolve("nextcloud").resolve("ECV"));
		Files.createDirectories(shared.resolve("data").resolve("events"));

		assertEquals(Optional.empty(), check(shared.toString()));
		assertEquals(Optional.empty(), check("  " + shared + "  "));
	}

	@Test
	void anEmptyExistingFolderPasses() throws IOException {
		Path shared = Files.createDirectories(root.resolve("leer"));

		assertEquals(Optional.empty(), check(shared.toString()));
	}
}
