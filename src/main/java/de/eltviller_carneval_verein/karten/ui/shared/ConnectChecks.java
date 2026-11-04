package de.eltviller_carneval_verein.karten.ui.shared;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;

import de.eltviller_carneval_verein.karten.validation.MessageTemplate;

/**
 * Die Prüfungen eines eingegebenen gemeinsamen Ordners vor dem Verbinden. Sie lesen nur und legen nie einen Ordner an. Weil
 * sie auf ein Netzlaufwerk zugreifen, die Oberfläche nicht darauf warten lassen (im Hintergrund aufrufen). Ohne JavaFX.
 */
public final class ConnectChecks {

	/** Die Unterordner der Daten; liegt einer direkt im gewählten Ordner, wurde vermutlich {@code data} selbst gewählt. */
	private static final String[] DATA_FOLDERS = { "events", "halls", "feeds" };

	private ConnectChecks() {
	}

	/**
	 * Prüft den eingegebenen Pfad in dieser Reihenfolge: leer, existiert nicht, kein Ordner, nicht erreichbar, überschneidet
	 * sich mit dem eigenen Datenordner (gleich, darin oder darüber), enthält direkt {@code events}, {@code halls} oder
	 * {@code feeds}.
	 *
	 * @param text       der Pfad, wie eingegeben (Leerzeichen am Rand zählen nicht); {@code null} gilt als leer
	 * @param appDataDir der eigene Datenordner dieses Rechners
	 * @param reachable  die Erreichbarkeitsprüfung des Dienstes (in der Anwendung {@code Reachability::isReachable})
	 * @return der Fehlertext mit dem Pfad, leer bei gültigem Ordner
	 */
	public static Optional<String> check(String text, Path appDataDir, Predicate<Path> reachable) {
		String typed = text == null ? "" : text.strip();
		if (typed.isEmpty()) {
			return Optional.of(SharedFolderTexts.CHECK_EMPTY);
		}
		Path folder;
		try {
			folder = Path.of(typed);
		} catch (InvalidPathException e) {
			return Optional.of(MessageTemplate.format(SharedFolderTexts.CHECK_MISSING, typed));
		}
		if (!Files.exists(folder)) {
			return Optional.of(MessageTemplate.format(SharedFolderTexts.CHECK_MISSING, typed));
		}
		if (!Files.isDirectory(folder)) {
			return Optional.of(MessageTemplate.format(SharedFolderTexts.CHECK_NOT_A_FOLDER, typed));
		}
		if (!reachable.test(folder)) {
			return Optional.of(MessageTemplate.format(SharedFolderTexts.CHECK_UNREACHABLE, typed));
		}
		if (overlaps(folder, appDataDir)) {
			return Optional.of(MessageTemplate.format(SharedFolderTexts.CHECK_OWN_DATA_DIR, typed));
		}
		for (String name : DATA_FOLDERS) {
			if (Files.isDirectory(folder.resolve(name))) {
				return Optional.of(MessageTemplate.format(SharedFolderTexts.CHECK_DATA_SUBFOLDER, typed));
			}
		}
		return Optional.empty();
	}

	/**
	 * Ob die Ordner gleich sind oder einer im anderen liegt, verglichen als normalisierte absolute Pfade. {@code Path.startsWith}
	 * vergleicht ganze Namensteile (ein Geschwister mit gleichem Namensanfang zählt nicht) und unter Windows ohne Rücksicht auf
	 * Groß-/Kleinschreibung.
	 */
	private static boolean overlaps(Path folder, Path appDataDir) {
		Path shared = folder.toAbsolutePath().normalize();
		Path own = appDataDir.toAbsolutePath().normalize();
		return shared.startsWith(own) || own.startsWith(shared);
	}
}
