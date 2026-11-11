package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PatchNotesMarkdownTest {

	private static String markdown(String... lines) {
		PatchNotes notes = PatchNotes.parse(String.join("\n", lines));
		return notes.markdown(notes.releases().get(0));
	}

	@Test
	void twoSectionsByteExact() {
		assertEquals("### Neu\n- Erster Eintrag\n- Zweiter\n\n### Behoben\n- Dritter\n",
				markdown("## 1.1.0", "### Neu", "- Erster Eintrag", "- Zweiter", "", "### Behoben", "- Dritter"));
	}

	@Test
	void continuationIsJoined() {
		assertEquals("### Neu\n- Erste Zeile und zweite Zeile\n",
				markdown("## 1.1.0", "### Neu", "- Erste Zeile", "  und zweite Zeile"));
	}

	@Test
	void punctuationIsEscaped() {
		assertEquals("### Neu\n- \\<Adresse\\> \\~a\\~ \\&auml\\; \\| 2\\. Vorstellung \\(gut\\)\\! „ä“\n",
				markdown("## 1.1.0", "### Neu", "- <Adresse> ~a~ &auml; | 2. Vorstellung (gut)! „ä“"));
	}

	@Test
	void everyAsciiPunctuationIsEscaped() {
		String punctuation = "!\"$%&'()*+,-./;<=>?[\\]^_`{|}~";
		StringBuilder expected = new StringBuilder();
		for (char c : punctuation.toCharArray()) {
			expected.append('\\').append(c);
		}
		assertEquals("### Neu\n- a" + expected + "\n", markdown("## 1.1.0", "### Neu", "- a" + punctuation));
	}
}
