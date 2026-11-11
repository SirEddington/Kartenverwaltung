package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

class PatchNotesExportTest {

	private static final PatchNotes NOTES = PatchNotes.parse(String.join("\n", "## 1.1.0", "### Neu", "- Größe ändern",
			"", "### Behoben", "- Fehler weg"));

	private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
	private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
	private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
	private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

	private int run(Supplier<PatchNotes> notes, String... args) {
		return PatchNotesExport.run(args, notes, out, err);
	}

	private String out() {
		return outBytes.toString(StandardCharsets.UTF_8);
	}

	private String err() {
		return errBytes.toString(StandardCharsets.UTF_8);
	}

	@Test
	void withAttachment() {
		assertEquals(0, run(() -> NOTES, "1.1.0", "[Download](x)"));
		assertEquals(NOTES.markdown(NOTES.releases().get(0)) + "\n[Download](x)\n", out());
	}

	@Test
	void withoutAttachment() {
		assertEquals(0, run(() -> NOTES, "1.1.0"));
		assertEquals(NOTES.markdown(NOTES.releases().get(0)), out());
	}

	@Test
	void umlautsSurviveAsUtf8() {
		run(() -> NOTES, "1.1.0");
		byte[] expected = "Größe".getBytes(StandardCharsets.UTF_8);
		byte[] all = outBytes.toByteArray();
		assertTrue(indexOf(all, expected) >= 0, out());
		assertArrayEquals(expected, java.util.Arrays.copyOfRange(all, indexOf(all, expected),
				indexOf(all, expected) + expected.length));
	}

	@Test
	void onlyLineFeeds() {
		run(() -> NOTES, "1.1.0", "[Download](x)");
		assertFalse(out().contains("\r"));
	}

	@Test
	void noArguments() {
		assertEquals(1, run(() -> NOTES));
		assertFalse(err().isBlank());
		assertEquals("", out());
	}

	@Test
	void tooManyArguments() {
		assertEquals(1, run(() -> NOTES, "1.1.0", "a", "b"));
		assertFalse(err().isBlank());
	}

	@Test
	void invalidVersionArgument() {
		assertEquals(1, run(() -> NOTES, "1.x"));
		assertFalse(err().isBlank());
	}

	@Test
	void versionNotInFile() {
		assertEquals(1, run(() -> NOTES, "9.9.9"));
		assertTrue(err().contains("9.9.9"), err());
	}

	@Test
	void invalidFile() {
		assertEquals(1, run(() -> {
			throw new InvalidPatchNotesException(3, "kaputt");
		}, "1.1.0"));
		assertTrue(err().contains("kaputt"), err());
	}

	private static int indexOf(byte[] all, byte[] part) {
		outer: for (int i = 0; i <= all.length - part.length; i++) {
			for (int j = 0; j < part.length; j++) {
				if (all[i + j] != part[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}
}
