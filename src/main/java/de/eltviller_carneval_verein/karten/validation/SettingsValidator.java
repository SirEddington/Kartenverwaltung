package de.eltviller_carneval_verein.karten.validation;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

import de.eltviller_carneval_verein.karten.settings.AppSettings;

/**
 * Plausibilitätsprüfungen für die Einstellungen (Kürzel SET, siehe {@link SettingsIssue}). Geprüft wird nur, was sich
 * ohne Zugriff auf das Dateisystem sagen lässt: ob der Spiegelordner den Standardordner berührt. Ob der Ordner
 * erreichbar und beschreibbar ist, zeigt sich erst beim Anlegen und meldet der Aufrufer selbst
 * ({@link SettingsIssue#MIRROR_DIR_UNREACHABLE}, {@link SettingsIssue#MIRROR_DIR_NOT_WRITABLE}).
 * Aufbau wie bei {@link HallValidator}.
 */
public final class SettingsValidator {

	private final AppSettings settings;
	private final Path defaultRoot;
	private final ValidationResult result = ValidationResult.ok();

	private SettingsValidator(AppSettings settings, Path defaultRoot) {
		this.settings = settings;
		this.defaultRoot = defaultRoot;
	}

	/** @param defaultRoot der Standardordner der Anwendung, in dem die Daten sonst liegen */
	public static ValidationResult validate(AppSettings settings, Path defaultRoot) {
		return new SettingsValidator(settings, defaultRoot).run();
	}

	private ValidationResult run() {
		validateMirrorDirNotNested();
		return result;
	}

	/** Leer oder nur Leerzeichen heißt "Spiegelung aus" und ist gültig. */
	private void validateMirrorDirNotNested() {
		String mirrorDir = settings.getMirrorDir();
		if (mirrorDir == null || mirrorDir.isBlank()) {
			return;
		}
		Path mirror;
		try {
			mirror = normalize(Path.of(mirrorDir.trim()));
		} catch (InvalidPathException e) {
			// Ein unbrauchbarer Pfad ist keine Verschachtelung; ob er erreichbar ist, klärt der Aufrufer (SET-002)
			return;
		}
		Path base = normalize(defaultRoot);
		if (mirror.startsWith(base) || base.startsWith(mirror)) {
			result.add(SettingsIssue.MIRROR_DIR_NESTED.toIssue(mirrorDir));
		}
	}

	/** Absolut und ohne "." und ".."; unter Windows ohne Beachtung der Groß-/Kleinschreibung. */
	private static Path normalize(Path path) {
		Path normalized = path.toAbsolutePath().normalize();
		if (System.getProperty("os.name", "").startsWith("Windows")) {
			return Path.of(normalized.toString().toLowerCase(Locale.ROOT));
		}
		return normalized;
	}
}
