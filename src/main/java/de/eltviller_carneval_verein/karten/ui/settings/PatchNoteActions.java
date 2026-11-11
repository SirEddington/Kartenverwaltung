package de.eltviller_carneval_verein.karten.ui.settings;

import java.net.URI;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.update.AppVersion;

/**
 * Was „Info und Updates“ für die Patchnotes braucht (KARV-125), als ein Parameter, damit {@link InfoSection#build}
 * nicht um vier weitere wächst.
 *
 * @param releasePage     die Release-Seite einer Version, leer ohne
 * @param openReleasePage öffnet eine Adresse im Browser; meldet einen Fehler mit dem Text für den Nutzer
 * @param showPatchNotes  öffnet das Fenster „Was ist neu“
 * @param showError       zeigt einen Fehler in der Fußzeile
 */
public record PatchNoteActions(Function<AppVersion, Optional<URI>> releasePage,
		BiConsumer<URI, Consumer<String>> openReleasePage, Runnable showPatchNotes, Consumer<String> showError) {
}
