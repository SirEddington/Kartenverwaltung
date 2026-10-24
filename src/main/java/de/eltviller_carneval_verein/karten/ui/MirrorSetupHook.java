package de.eltviller_carneval_verein.karten.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.Callable;

import de.eltviller_carneval_verein.karten.AppPaths;
import de.eltviller_carneval_verein.karten.mirror.MirrorService;
import de.eltviller_carneval_verein.karten.mirror.MirrorSetup;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.settings.AppSettings;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.validation.ValidationResult;

/**
 * Verbindet {@link SettingsSaver} mit dem echten {@link MirrorService} und den Dialogen. Bewusst dünn: Alle
 * Entscheidungen stecken in {@link MirrorSetup} und {@link SettingsSaver#trySave}, hier nur die Abfolge samt
 * Rückfragen. Läuft im JavaFX-Thread.
 */
final class MirrorSetupHook implements SettingsSaver.MirrorHook {

	/** Ergebnis der Prüfung und des Vergleichs; {@code action} ist {@code null}, wenn die Prüfung scheiterte. */
	private record Plan(ValidationResult check, MirrorSetup.Action action) {
	}

	@Override
	public ValidationResult prepare(AppSettings settings) throws IOException {
		String dir = settings.getMirrorDir() == null ? "" : settings.getMirrorDir().trim();
		// Leer heißt aus (nichts einzurichten), unverändert heißt: Der Ordner ist schon eingerichtet
		if (dir.isEmpty() || dir.equals(MirrorService.getInstance().target())) {
			return ValidationResult.ok();
		}
		Path local = AppPaths.appDataDir().toPath();

		// Prüfen und Vergleichen greifen auf den Spiegelordner zu (ggf. Netzlaufwerk) und laufen deshalb hinter dem Wartedialog
		Plan plan = call(() -> {
			ValidationResult check = MirrorSetup.check(dir);
			return check.isValid() ? new Plan(check, MirrorSetup.decide(local, Path.of(dir))) : new Plan(check, null);
		});
		if (!plan.check().isValid()) {
			return plan.check();
		}

		MirrorSetup.Action action = plan.action();
		if (action == MirrorSetup.Action.ASK) {
			action = MirrorSetup.resolve(MirrorDialogs.askConflict(dir));
		}
		MirrorSetup.Action chosen = action;
		if (chosen != MirrorSetup.Action.NOTHING) {
			call(() -> {
				MirrorSetup.execute(chosen, local, Path.of(dir), LocalDateTime.now());
				return null;
			});
		}

		if (chosen == MirrorSetup.Action.ADOPT_TARGET) {
			// Die lokalen Dateien wurden ersetzt: Zwischenspeicher der Repositories und gemerkte Stände verwerfen
			JsonEventRepository.getInstance().reloadEvents();
			JsonHallRepository.getInstance().reloadHalls();
			ChangeTracker.events().reset();
			ChangeTracker.halls().reset();
		}
		return plan.check();
	}

	@Override
	public void activate(AppSettings settings) {
		// Ein unveränderter Ordner bleibt unberührt: configure würde Ausfallmeldungen und Warteschlange zurücksetzen
		if (needsConfigure(settings.getMirrorDir(), MirrorService.getInstance().target())) {
			MirrorService.getInstance().configure(settings.getMirrorDir());
		}
	}

	/** Ob der Spiegelordner aus den Einstellungen vom aktiven abweicht (Leerzeichen am Rand zählen nicht, leer = aus). */
	static boolean needsConfigure(String mirrorDir, String currentTarget) {
		String wanted = mirrorDir == null || mirrorDir.isBlank() ? null : mirrorDir.trim();
		String active = currentTarget == null || currentTarget.isBlank() ? null : currentTarget.trim();
		return !Objects.equals(wanted, active);
	}

	// Der Wartedialog wirft Exception; der Vertrag von prepare kennt nur IOException
	private static <T> T call(Callable<T> work) throws IOException {
		try {
			return MirrorDialogs.runWithWaitDialog("Bitte warten …", work);
		} catch (IOException | RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException(e.getMessage(), e);
		}
	}
}
