package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Die Patchnotes der App (KARV-125), gelesen aus {@code patchnotes.md} in den Ressourcen. Die Datei hat eine feste,
 * bewusst kleine Form (Versionen, vier Abschnitte, Einträge als reiner Text); jede Abweichung ist ein Fehler mit
 * Zeilennummer, damit kein Release mit kaputten oder unfertigen Notes entsteht. Ohne JavaFX.
 */
public final class PatchNotes {

	private static final String RESOURCE = "/de/eltviller_carneval_verein/karten/patchnotes.md";

	private static final Pattern PLACEHOLDER_WORD = Pattern.compile("\\b(TODO|TBD|XXX)\\b",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
	/** Was GitHub auf dem gerenderten Text als Erwähnung, Verweis oder Emoji-Kürzel auflöst. */
	private static final Pattern GITHUB_MAGIC = Pattern.compile("@[\\p{L}\\p{N}_]|#\\d|(?<![0-9]):[A-Za-z0-9_+-]+:");

	/** Die Abschnitte einer Version, in der vorgeschriebenen Reihenfolge. */
	public enum Kind {
		NEU("Neu"), VERBESSERT("Verbessert"), BEHOBEN("Behoben"), HINTER_DEN_KULISSEN("Hinter den Kulissen");

		private final String title;

		Kind(String title) {
			this.title = title;
		}

		/** Die Überschrift in der Datei und im Fenster. */
		public String title() {
			return title;
		}

		private static Optional<Kind> ofTitle(String title) {
			for (Kind kind : values()) {
				if (kind.title.equals(title)) {
					return Optional.of(kind);
				}
			}
			return Optional.empty();
		}
	}

	/** Ein Abschnitt mit mindestens einem Eintrag; Fortsetzungszeilen sind schon angehängt. */
	public record Section(Kind kind, List<String> entries) {
		public Section {
			entries = List.copyOf(entries);
		}
	}

	/** Eine Version (Revision 0, Text wie in der Überschrift) mit mindestens einem Abschnitt. */
	public record Release(AppVersion version, List<Section> sections) {
		public Release {
			sections = List.copyOf(sections);
		}
	}

	private final List<Release> releases;

	private PatchNotes(List<Release> releases) {
		this.releases = List.copyOf(releases);
	}

	/** Die Patchnotes in der App. */
	public static PatchNotes bundled() {
		try (InputStream in = PatchNotes.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new InvalidPatchNotesException(0, "Datei fehlt");
			}
			return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new InvalidPatchNotesException(0, "Datei nicht lesbar: " + e.getMessage());
		}
	}

	/**
	 * Liest den Text einer Patchnotes-Datei.
	 *
	 * @throws InvalidPatchNotesException bei jeder Regelverletzung, mit Zeile und Grund
	 */
	public static PatchNotes parse(String text) {
		return new Parser().parse(text.startsWith("﻿") ? text.substring(1) : text);
	}

	/** Alle Versionen, die neueste zuerst. */
	public List<Release> releases() {
		return releases;
	}

	/** Die Version {@code v}; nur die Versionsnummer zählt, nicht die Revision. */
	public Optional<Release> of(AppVersion v) {
		return releases.stream().filter(r -> base(r.version()).compareTo(base(v)) == 0).findFirst();
	}

	/** Alle Versionen bis einschließlich {@code v} (ohne Revision), die neueste zuerst. */
	public List<Release> upTo(AppVersion v) {
		return releases.stream().filter(r -> base(r.version()).compareTo(base(v)) <= 0).toList();
	}

	/**
	 * Alle Versionen nach {@code seen} bis einschließlich {@code upTo} (ohne Revision), die neueste zuerst; ohne
	 * {@code seen} wie {@link #upTo(AppVersion)}.
	 */
	public List<Release> after(Optional<AppVersion> seen, AppVersion upTo) {
		return upTo(upTo).stream().filter(r -> seen.isEmpty() || base(r.version()).compareTo(base(seen.get())) > 0)
				.toList();
	}

	static AppVersion base(AppVersion v) {
		return new AppVersion(v.version(), 0);
	}

	/** Ein Durchlauf über die Zeilen; hält fest, wo er gerade steht. */
	private static final class Parser {

		private final List<Release> releases = new ArrayList<>();
		private AppVersion version;
		private int versionLine;
		private final List<Section> sections = new ArrayList<>();
		private Kind kind;
		private int kindLine;
		private final List<String> entries = new ArrayList<>();
		private StringBuilder entry;
		private int entryLine;
		private boolean previousBlank;
		private boolean seenContent;

		PatchNotes parse(String text) {
			int number = 0;
			for (String raw : text.lines().toList()) {
				number++;
				if (raw.indexOf('\t') >= 0) {
					throw new InvalidPatchNotesException(number, "Tabulatoren sind nicht erlaubt");
				}
				String line = raw.stripTrailing();
				if (line.isEmpty()) {
					previousBlank = true;
					continue;
				}
				line(number, line);
				previousBlank = false;
				seenContent = true;
			}
			closeVersion();
			if (releases.isEmpty()) {
				throw new InvalidPatchNotesException(0, "keine Version");
			}
			return new PatchNotes(releases);
		}

		private void line(int number, String line) {
			if (line.startsWith("# ")) {
				if (seenContent) {
					throw new InvalidPatchNotesException(number, "ein Titel „# …“ ist nur in der ersten Zeile erlaubt");
				}
			} else if (line.startsWith("## ")) {
				version(number, line.substring(3));
			} else if (line.startsWith("### ")) {
				section(number, line.substring(4));
			} else if (line.startsWith("  ")) {
				continuation(number, line.strip());
			} else if (line.equals("-")) {
				throw new InvalidPatchNotesException(number, "leerer Eintrag");
			} else if (line.startsWith("- ")) {
				entry(number, line.substring(2).strip());
			} else {
				throw new InvalidPatchNotesException(number,
						"erwartet wird „## Version“, „### Abschnitt“, „- Eintrag“ oder eine eingerückte Fortsetzung");
			}
		}

		private void version(int number, String text) {
			closeVersion();
			AppVersion parsed = AppVersion.parse(text, null)
					.orElseThrow(() -> new InvalidPatchNotesException(number, "ungültige Version „" + text + "“"));
			if (!releases.isEmpty() && base(parsed).compareTo(base(releases.get(releases.size() - 1).version())) >= 0) {
				throw new InvalidPatchNotesException(number,
						"die Versionen müssen streng absteigend stehen (neueste oben, jede nur einmal)");
			}
			version = parsed;
			versionLine = number;
		}

		private void section(int number, String title) {
			if (version == null) {
				throw new InvalidPatchNotesException(number, "Abschnitt vor der ersten Version");
			}
			Kind next = Kind.ofTitle(title).orElseThrow(() -> new InvalidPatchNotesException(number,
					"unbekannter Abschnitt „" + title + "“ (erlaubt: Neu, Verbessert, Behoben, Hinter den Kulissen)"));
			closeSection();
			if (!sections.isEmpty() && sections.get(sections.size() - 1).kind().compareTo(next) >= 0) {
				throw new InvalidPatchNotesException(number,
						"Abschnitte nur in der Reihenfolge Neu, Verbessert, Behoben, Hinter den Kulissen und je einmal");
			}
			kind = next;
			kindLine = number;
		}

		private void entry(int number, String text) {
			if (kind == null) {
				throw new InvalidPatchNotesException(number, "Eintrag vor dem ersten Abschnitt");
			}
			if (text.isEmpty()) {
				throw new InvalidPatchNotesException(number, "leerer Eintrag");
			}
			closeEntry();
			entry = new StringBuilder(text);
			entryLine = number;
		}

		private void continuation(int number, String text) {
			if (entry == null || previousBlank) {
				throw new InvalidPatchNotesException(number, "eingerückte Fortsetzung ohne Eintrag direkt darüber");
			}
			if (text.startsWith("- ") || text.equals("-")) {
				throw new InvalidPatchNotesException(number, "verschachtelte Listen gibt es nicht");
			}
			entry.append(' ').append(text);
		}

		private void closeEntry() {
			if (entry == null) {
				return;
			}
			String text = entry.toString();
			if (text.equals("…") || text.equals("...") || PLACEHOLDER_WORD.matcher(text).find()) {
				throw new InvalidPatchNotesException(entryLine, "Platzhalter statt eines echten Eintrags");
			}
			if (GITHUB_MAGIC.matcher(text).find()) {
				throw new InvalidPatchNotesException(entryLine,
						"sieht für GitHub wie eine Erwähnung, ein Verweis oder ein Emoji-Kürzel aus (@name, #12, :wort:)");
			}
			entries.add(text);
			entry = null;
		}

		private void closeSection() {
			closeEntry();
			if (kind == null) {
				return;
			}
			if (entries.isEmpty()) {
				throw new InvalidPatchNotesException(kindLine, "Abschnitt ohne Einträge (dann weglassen)");
			}
			sections.add(new Section(kind, entries));
			entries.clear();
			kind = null;
		}

		private void closeVersion() {
			closeSection();
			if (version == null) {
				return;
			}
			if (sections.isEmpty()) {
				throw new InvalidPatchNotesException(versionLine, "Version ohne Abschnitt");
			}
			releases.add(new Release(version, sections));
			sections.clear();
			version = null;
		}
	}
}
