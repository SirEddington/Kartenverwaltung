package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** KARV-195: Der Computername kommt unter Linux nicht aus {@code COMPUTERNAME}. */
class ComputerNameTest {

	private static final Supplier<String> NO_FILE = () -> null;

	@Test
	void windowsUsesComputernameAndDoesNotReadTheFile() {
		Supplier<String> mustNotRead = () -> {
			throw new AssertionError("/etc/hostname gelesen");
		};

		assertEquals("KASSE-1", ComputerName.find(Map.of("COMPUTERNAME", "KASSE-1")::get, mustNotRead));
	}

	@Test
	void linuxUsesEtcHostnameBeforeHostname() {
		assertEquals("kasse-2", ComputerName.find(Map.of("HOSTNAME", "aus-der-shell")::get, () -> "kasse-2"));
	}

	@Test
	void hostnameVariableIsTheLastSource() {
		assertEquals("kasse-3", ComputerName.find(Map.of("HOSTNAME", "kasse-3")::get, NO_FILE));
	}

	@Test
	void blankValuesCountAsMissing() {
		assertEquals("kasse-4", ComputerName.find(Map.of("COMPUTERNAME", "  ")::get, () -> "kasse-4"));
		assertEquals("kasse-5", ComputerName.find(Map.of("HOSTNAME", "kasse-5")::get, () -> " "));
	}

	@Test
	void localhostIsNoName() {
		assertEquals("kasse-7", ComputerName.find(Map.of("HOSTNAME", "kasse-7")::get, () -> "localhost"));
		assertNull(ComputerName.find(Map.of("HOSTNAME", "LOCALHOST.localdomain")::get, () -> "localhost.localdomain"));
		assertEquals("localhost-kasse", ComputerName.find(name -> null, () -> "localhost-kasse"), "nur der ganze Name");
	}

	@Test
	void withoutAnySourceTheResultIsNull() {
		assertNull(ComputerName.find(name -> null, NO_FILE), "MachineIdentity setzt dann „Rechner“");
	}

	@Test
	void firstLineSkipsEmptyLinesAndComments(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("hostname"), "\n# von Hand gesetzt\n  kasse-6  \nzweite\n",
				StandardCharsets.UTF_8);

		assertEquals("kasse-6", ComputerName.firstLine(file));
	}

	@Test
	void firstLineOfAMissingEmptyOrUnreadableFileIsNull(@TempDir Path dir) throws IOException {
		assertNull(ComputerName.firstLine(dir.resolve("fehlt")));
		assertNull(ComputerName.firstLine(Files.writeString(dir.resolve("leer"), "\n# nur Kommentar\n")));
		assertNull(ComputerName.firstLine(Files.createDirectory(dir.resolve("ordner"))), "ein Ordner ist unlesbar");
	}
}
