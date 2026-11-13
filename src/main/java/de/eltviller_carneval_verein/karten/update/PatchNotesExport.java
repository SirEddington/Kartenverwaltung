package de.eltviller_carneval_verein.karten.update;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Gibt die Patchnotes einer Version als Markdown aus, für den Text des GitHub-Releases (KARV-125, aufgerufen von
 * {@code .github/scripts/release-notes.sh}). Aufruf {@code PatchNotesExport <Version> [<Anhang>]}; der Anhang (der
 * Download-Link) kommt unverändert nach einer Leerzeile dazu. Ausgabe UTF-8, nur mit {@code \n}; jeder Fehler endet
 * mit Exit-Code 1, damit der Release-Lauf vor der Veröffentlichung abbricht.
 */
public final class PatchNotesExport {

	private PatchNotesExport() {
	}

	public static void main(String[] args) {
		PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
		int code = run(args, PatchNotes::bundled, out, System.err);
		out.flush();
		if (out.checkError()) {
			System.err.print("Die Ausgabe ließ sich nicht schreiben.\n");
			code = 1;
		}
		System.exit(code);
	}

	/** Die eigentliche Arbeit; liefert den Exit-Code. */
	static int run(String[] args, Supplier<PatchNotes> notes, PrintStream out, PrintStream err) {
		if (args.length < 1 || args.length > 2) {
			err.print("Aufruf: PatchNotesExport <Version> [<Anhang>]\n");
			return 1;
		}
		Optional<AppVersion> version = AppVersion.parse(args[0], null);
		if (version.isEmpty()) {
			err.print("Ungültige Version: " + args[0] + "\n");
			return 1;
		}
		try {
			PatchNotes all = notes.get();
			Optional<PatchNotes.Release> release = all.of(version.get());
			if (release.isEmpty()) {
				err.print("In patchnotes.md fehlt die Version " + args[0] + "\n");
				return 1;
			}
			StringBuilder text = new StringBuilder(all.markdown(release.get()));
			if (args.length == 2) {
				text.append('\n').append(args[1]).append('\n');
			}
			out.print(text);
			return 0;
		} catch (InvalidPatchNotesException e) {
			err.print(e.getMessage() + "\n");
			return 1;
		}
	}
}
