package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.update.PatchNotes.Kind;
import de.eltviller_carneval_verein.karten.update.PatchNotes.Release;

class PatchNotesTest {

	private static PatchNotes parse(String... lines) {
		return PatchNotes.parse(String.join("\n", lines));
	}

	private static AppVersion v(String version) {
		return AppVersion.parse(version, null).orElseThrow();
	}

	/** Prüft, dass die Datei ungültig ist und die Meldung die erwartete Zeile nennt. */
	private static InvalidPatchNotesException invalidAt(int line, String... lines) {
		InvalidPatchNotesException e = assertThrows(InvalidPatchNotesException.class, () -> parse(lines));
		assertEquals(line, e.line(), e.getMessage());
		return e;
	}

	@Test
	void specExampleIsValid() {
		PatchNotes notes = parse("# Patchnotes", "", "## 1.1.0", "", "### Neu",
				"- Die Auswahl im Menü „Anzeige“ bleibt erhalten, auch nach einem Neustart.",
				"- Ein längerer Eintrag darf über mehrere Zeilen gehen; jede Fortsetzungszeile",
				"  beginnt mit zwei Leerzeichen.", "", "### Hinter den Kulissen",
				"- Daten, die eine neuere Version geschrieben hat, bleiben beim Speichern erhalten.");
		assertEquals(1, notes.releases().size());
		Release release = notes.releases().get(0);
		assertEquals("1.1.0", release.version().version());
		assertEquals(List.of(Kind.NEU, Kind.HINTER_DEN_KULISSEN),
				release.sections().stream().map(PatchNotes.Section::kind).toList());
		assertEquals("Ein längerer Eintrag darf über mehrere Zeilen gehen; jede Fortsetzungszeile beginnt mit zwei "
				+ "Leerzeichen.", release.sections().get(0).entries().get(1));
	}

	@Test
	void bomAndCrlfAreAccepted() {
		PatchNotes notes = PatchNotes.parse("﻿# T\r\n## 1.1.0\r\n### Neu\r\n- A\r\n");
		assertEquals(List.of("A"), notes.releases().get(0).sections().get(0).entries());
	}

	@Test
	void trailingSpacesAfterVersionAreIgnored() {
		PatchNotes notes = parse("## 1.1.0 ", "### Neu ", "- A  ");
		assertEquals("1.1.0", notes.releases().get(0).version().version());
		assertEquals(List.of("A"), notes.releases().get(0).sections().get(0).entries());
	}

	@Test
	void blankLineOfSpacesIsABlankLine() {
		PatchNotes notes = parse("## 1.1.0", "   ", "### Neu", "- A", "    ", "- B");
		assertEquals(List.of("A", "B"), notes.releases().get(0).sections().get(0).entries());
	}

	@Test
	void realGermanPunctuationIsValid() {
		String entry = "„Mehr …“ – jetzt auch per E-Mail, ab 19:30-22:00 Uhr bzw. 19:30:00";
		PatchNotes notes = parse("## 1.1.0", "### Neu", "- " + entry, "- Fotodokumentation");
		assertEquals(List.of(entry, "Fotodokumentation"), notes.releases().get(0).sections().get(0).entries());
	}

	@Test
	void unknownHeading() {
		invalidAt(2, "## 1.1.0", "### Sonstiges", "- A");
	}

	@Test
	void sectionsInWrongOrder() {
		invalidAt(4, "## 1.1.0", "### Behoben", "- A", "### Neu", "- B");
	}

	@Test
	void duplicateSection() {
		invalidAt(4, "## 1.1.0", "### Neu", "- A", "### Neu", "- B");
	}

	@Test
	void emptySection() {
		invalidAt(2, "## 1.1.0", "### Neu", "### Behoben", "- A");
	}

	@Test
	void emptySectionAtEnd() {
		invalidAt(4, "## 1.1.0", "### Neu", "- A", "### Behoben");
	}

	@Test
	void versionWithoutSection() {
		invalidAt(1, "## 1.1.0", "## 1.0.9", "### Neu", "- A");
	}

	@Test
	void fileWithoutVersion() {
		invalidAt(0, "# Patchnotes", "");
	}

	@Test
	void sectionBeforeFirstVersion() {
		invalidAt(1, "### Neu", "- A");
	}

	@Test
	void entryBeforeFirstSection() {
		invalidAt(2, "## 1.1.0", "- A");
	}

	@Test
	void invalidVersion() {
		invalidAt(1, "## 1.x", "### Neu", "- A");
	}

	@Test
	void sameVersionWrittenTwoWays() {
		invalidAt(4, "## 1.1.0", "### Neu", "- A", "## 1.1", "### Neu", "- B");
	}

	@Test
	void versionsNotDescending() {
		invalidAt(4, "## 1.0.1", "### Neu", "- A", "## 1.1.0", "### Neu", "- B");
	}

	@Test
	void textWithoutDash() {
		invalidAt(3, "## 1.1.0", "### Neu", "Text");
	}

	@Test
	void dashWithoutSpace() {
		invalidAt(3, "## 1.1.0", "### Neu", "-Text");
	}

	@Test
	void emptyEntry() {
		invalidAt(3, "## 1.1.0", "### Neu", "- ");
		invalidAt(3, "## 1.1.0", "### Neu", "-");
	}

	@Test
	void tab() {
		invalidAt(3, "## 1.1.0", "### Neu", "-\tA");
	}

	@Test
	void tabAtLineEnd() {
		invalidAt(3, "## 1.1.0", "### Neu", "- A\t");
	}

	@Test
	void continuationAfterBlankLine() {
		invalidAt(5, "## 1.1.0", "### Neu", "- A", "", "  weiter");
	}

	@Test
	void continuationRightAfterHeading() {
		invalidAt(3, "## 1.1.0", "### Neu", "  weiter");
	}

	@Test
	void nestedListAsContinuation() {
		invalidAt(4, "## 1.1.0", "### Neu", "- A", "  - Unterpunkt");
	}

	@Test
	void secondTitle() {
		invalidAt(2, "# Patchnotes", "# Noch einer", "## 1.1.0", "### Neu", "- A");
	}

	@Test
	void placeholders() {
		for (String placeholder : List.of("…", "...", "TODO später", "noch tbd", "XXX")) {
			invalidAt(3, "## 1.1.0", "### Neu", "- " + placeholder);
		}
	}

	@Test
	void mentionsReferencesAndEmoji() {
		for (String entry : List.of("Danke @name", "siehe #38", "Fertig :tada:", "Gut :+1:", "Super :100:")) {
			InvalidPatchNotesException e = invalidAt(3, "## 1.1.0", "### Neu", "- " + entry);
			assertTrue(e.getMessage().contains("sieht für GitHub wie eine Erwähnung, ein Verweis oder ein Emoji-Kürzel aus"),
					e.getMessage());
		}
	}

	@Test
	void ofIgnoresRevision() {
		PatchNotes notes = parse("## 1.1.0", "### Neu", "- A");
		assertTrue(notes.of(AppVersion.parse("1.1.0", "3").orElseThrow()).isPresent());
		assertFalse(notes.of(v("1.2.0")).isPresent());
	}

	@Test
	void upToAndAfter() {
		PatchNotes notes = parse("## 1.2.0", "### Neu", "- A", "## 1.1.1", "### Neu", "- B", "## 1.1.0", "### Neu",
				"- C");
		assertEquals(List.of("1.1.1", "1.1.0"), versions(notes.upTo(v("1.1.1"))));
		assertEquals(List.of("1.2.0", "1.1.1"), versions(notes.after(Optional.of(v("1.1.0")), v("1.2.0"))));
		assertEquals(List.of("1.1.1", "1.1.0"), versions(notes.after(Optional.empty(), v("1.1.1"))));
		assertEquals(List.of(), versions(notes.after(Optional.of(v("1.2.0")), v("1.2.0"))));
		assertEquals(List.of("1.2.0"), versions(notes.upTo(AppVersion.parse("1.2.0", "5").orElseThrow()).subList(0, 1)));
	}

	@Disabled("bis Task 4: die Datei kommt erst mit dem freigegebenen Text")
	@Test
	void bundledReadsTheResource() {
		assertFalse(PatchNotes.bundled().releases().isEmpty());
	}

	private static List<String> versions(List<Release> releases) {
		return releases.stream().map(r -> r.version().version()).toList();
	}
}
