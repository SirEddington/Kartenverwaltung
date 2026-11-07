package de.eltviller_carneval_verein.karten.ui.shared;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.update.DesktopOps;
import de.eltviller_carneval_verein.karten.update.Grund;
import de.eltviller_carneval_verein.karten.update.Installation;
import de.eltviller_carneval_verein.karten.update.UpdateInfo;
import de.eltviller_carneval_verein.karten.update.UpdateSource;

/**
 * Der Ablauf eines Updates aus der App (KARV-131), ohne JavaFX wie {@link ConnectFlow}: Rückfrage, offene Eingaben
 * sichern ({@code confirmClose}), Busy-Ansicht, dann im Hintergrund frisch prüfen, die {@code .appinstaller}-Datei laden
 * und öffnen (bzw. auf dem Browser-Weg die Adresse im Browser zeigen) und die App beenden. Scheitert etwas, geht es ohne
 * weitere Frage ins Hauptmenü, und die Fußzeile zeigt den Fehler. Es läuft nie mehr als ein Ablauf; während des
 * Verbindens mit dem gemeinsamen Ordner startet keiner.
 * <p>
 * {@link #update} und {@link #updateViaBrowser} laufen auf dem FX-Thread, die Netz- und Desktop-Aufrufe auf
 * {@code worker}, das Ergebnis wieder über {@code ui}.
 */
public final class UpdateFlow {

	private static final Logger LOG = Logger.getLogger(UpdateFlow.class.getName());

	/** Warum ein Ablauf abbrach; trägt den Text für die Fußzeile. */
	private static final class Stop extends Exception {
		private static final long serialVersionUID = 1L;

		Stop(String text) {
			super(text, null, false, false);
		}
	}

	private interface Work {
		void run() throws Exception;
	}

	private final Installation inst;
	private final Confirmation confirm;
	private final BooleanSupplier sharedFolderConfigured;
	private final BooleanSupplier confirmClose;
	private final Consumer<String> showBusy;
	private final UpdateSource source;
	private final DesktopOps desktop;
	private final Runnable exitApp;
	private final Runnable backToMenu;
	private final Runnable afterAbort;
	private final Runnable recheck;
	private final Consumer<String> showError;
	private final BooleanSupplier busyViewStillShown;
	private final BooleanSupplier connecting;
	private final Executor ui;
	private final Executor worker;
	private boolean running;

	/**
	 * @param confirm                die Rückfrage (Titel, Text, Knopf)
	 * @param sharedFolderConfigured ob ein gemeinsamer Ordner eingetragen ist (Satz zu mehreren Rechnern)
	 * @param confirmClose           sichert offene Eingaben wie beim Schließen des Fensters; {@code false} = Abbrechen
	 * @param showBusy               zeigt die Ansicht „Update wird vorbereitet …“
	 * @param exitApp                beendet die App ({@code Platform.exit})
	 * @param backToMenu             zeigt das Hauptmenü ohne Rückfrage
	 * @param afterAbort             nach Nein, Abbruch oder Fehler (in der App {@code conflictsChanged})
	 * @param recheck                stößt eine neue Prüfung an
	 * @param showError              zeigt einen Fehler in der Fußzeile
	 * @param busyViewStillShown     ob die Busy-Ansicht noch die aktuelle ist und das Fenster nicht geschlossen wurde
	 * @param connecting             ob gerade mit dem gemeinsamen Ordner verbunden wird
	 * @param ui                     führt auf dem FX-Thread aus
	 * @param worker                 führt eine Aufgabe auf einem eigenen Hintergrund-Thread aus
	 */
	public UpdateFlow(Installation inst, Confirmation confirm, BooleanSupplier sharedFolderConfigured,
			BooleanSupplier confirmClose, Consumer<String> showBusy, UpdateSource source, DesktopOps desktop,
			Runnable exitApp, Runnable backToMenu, Runnable afterAbort, Runnable recheck, Consumer<String> showError,
			BooleanSupplier busyViewStillShown, BooleanSupplier connecting, Executor ui, Executor worker) {
		this.inst = inst;
		this.confirm = confirm;
		this.sharedFolderConfigured = sharedFolderConfigured;
		this.confirmClose = confirmClose;
		this.showBusy = showBusy;
		this.source = source;
		this.desktop = desktop;
		this.exitApp = exitApp;
		this.backToMenu = backToMenu;
		this.afterAbort = afterAbort;
		this.recheck = recheck;
		this.showError = showError;
		this.busyViewStillShown = busyViewStillShown;
		this.connecting = connecting;
		this.ui = ui;
		this.worker = worker;
	}

	/** Ob gerade ein Ablauf läuft (nach Erfolg bis zum Ende der App). */
	public boolean isRunning() {
		return running;
	}

	/** Der direkte Weg: frisch prüfen, {@code .appinstaller} laden und öffnen, App beenden. Nur bei passender Paketfamilie. */
	public void update(UpdateInfo info) {
		if (!inst.canUpdateInPlace(info)) {
			return;
		}
		start(UpdateTexts.CONFIRM_DIRECT_TITLE,
				UpdateTexts.confirmDirectText(info.version(), sharedFolderConfigured.getAsBoolean()),
				UpdateTexts.BUTTON_UPDATE, () -> {
					UpdateInfo fresh = source.fetchInfo();
					if (fresh.version().compareTo(inst.version()) <= 0) {
						throw new Stop(UpdateTexts.ERROR_NO_NEWER);
					}
					if (!inst.canUpdateInPlace(fresh)) {
						throw new Stop(UpdateTexts.ERROR_REINSTALL);
					}
					Path file = source.downloadAppInstaller();
					desktop.open(file);
				}, UpdateTexts::errorDirect);
	}

	/** Der Browser-Weg: die Adresse der {@code .appinstaller}-Datei im Browser zeigen, App beenden; ohne eigene Prüfung. */
	public void updateViaBrowser() {
		start(UpdateTexts.CONFIRM_BROWSER_TITLE, UpdateTexts.confirmBrowserText(sharedFolderConfigured.getAsBoolean()),
				UpdateTexts.BUTTON_BROWSER, () -> desktop.browse(source.appInstallerUri()), grund -> UpdateTexts.ERROR_BROWSER);
	}

	private void start(String title, String text, String button, Work work, java.util.function.Function<Grund, String> errorText) {
		if (running || connecting.getAsBoolean()) {
			return;
		}
		running = true;
		boolean closed = false;
		try {
			if (!confirm.ask(title, text, button)) {
				abort();
				return;
			}
			if (!confirmClose.getAsBoolean()) {
				abort();
				return;
			}
			closed = true;
			showBusy.accept(UpdateTexts.BUSY);
			worker.execute(() -> runWork(work, errorText));
		} catch (Throwable e) {
			LOG.log(Level.WARNING, "Update-Ablauf vor dem Start abgebrochen", e);
			if (closed) {
				backToMenu.run();
			}
			showError.accept(errorText.apply(Grund.of(e)));
			abort();
		}
	}

	private void runWork(Work work, java.util.function.Function<Grund, String> errorText) {
		String error;
		boolean recheckAfter = false;
		try {
			work.run();
			error = null;
		} catch (Stop stop) {
			error = stop.getMessage();
			recheckAfter = true;
			LOG.info("Update nicht gestartet: " + error);
		} catch (Throwable e) {
			error = errorText.apply(Grund.of(e));
			LOG.log(Level.WARNING, "Update konnte nicht gestartet werden", e);
		}
		String shown = error;
		boolean again = recheckAfter;
		ui.execute(() -> finish(shown, again));
	}

	private void finish(String error, boolean recheckAfter) {
		if (error == null) {
			exitApp.run();
			return;
		}
		try {
			if (busyViewStillShown.getAsBoolean()) {
				backToMenu.run();
				showError.accept(error);
			} else {
				LOG.info("Fehler des Updates nicht angezeigt (Ansicht gewechselt oder Fenster geschlossen): " + error);
			}
			if (recheckAfter) {
				recheck.run();
			}
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Rückweg nach gescheitertem Update scheiterte", e);
		} finally {
			abort();
		}
	}

	/** Gibt den Ablauf frei und meldet den Abbruch weiter. */
	private void abort() {
		running = false;
		try {
			afterAbort.run();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Nacharbeit nach dem Update-Ablauf scheiterte", e);
		}
	}
}
