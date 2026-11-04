package de.eltviller_carneval_verein.karten.ui;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.validation.Severity;
import de.eltviller_carneval_verein.karten.validation.ValidationIssue;
import de.eltviller_carneval_verein.karten.validation.ValidationResult;
import javafx.animation.PauseTransition;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Alert.AlertType;
import javafx.util.Duration;

/**
 * Zentrale, nicht blockierende Meldung für die Fußzeile. Jeder Screen mit Footer enthält ein
 * {@link StatusLabel}, das diese Meldung anzeigt; die Controller schreiben nur hierher und
 * müssen das Label nicht kennen. Es gibt immer höchstens eine aktuelle Meldung, eine neue
 * ersetzt die alte. Fehler und Warnungen bleiben stehen, bis eine andere Meldung kommt oder
 * der Screen wechselt ({@link #clear()}); Info und Erfolg verschwinden nach einigen Sekunden. Ausnahme: Eine Meldung
 * des Hintergrunds ({@link #showInFooter}) blendet über einen Fehler nur kurz, dann steht er wieder da.
 *
 * Zeigt gerade kein Screen ein {@link StatusLabel}, wird die Meldung stattdessen als Dialog
 * ausgegeben, damit sie nicht verloren geht. Im Hauptfenster hat jede Ansicht eines: Ansichten ohne
 * eigene Fußzeile bekommen eine ohne Trennlinie ({@code MainApp}).
 */
public final class StatusMessage {

	/** Eine Zeile des Tooltips mit dem Schweregrad der jeweiligen Meldung (bestimmt ihre Farbe). */
	public record Line(Severity severity, String text) {
	}

	/**
	 * Eine anzuzeigende Meldung.
	 *
	 * @param severity Schweregrad (bestimmt die Farbe)
	 * @param text     Text für die Fußzeile
	 * @param details  vollständiger Text als reiner Text (bei mehreren Meldungen je eine pro Zeile), z. B. für den Dialog
	 * @param lines    die Zeilen des Tooltips, jede mit eigenem Schweregrad
	 */
	public record Entry(Severity severity, String text, String details, List<Line> lines) {

		/** Einzelne Meldung: der Tooltip zeigt denselben Text in der Farbe des Schweregrads. */
		public Entry(Severity severity, String text, String details) {
			this(severity, text, details, List.of(new Line(severity, details)));
		}
	}

	private static final StatusMessage INSTANCE = new StatusMessage();
	private static final Duration AUTO_CLEAR_AFTER = Duration.seconds(5);
	private static final int MAX_DETAIL_LINES = 20;

	/** Führt etwas nach einer Wartezeit aus (das Ausblenden einer Meldung); paketsichtbar für Tests. */
	interface Delay {
		/** Führt {@code action} nach {@code delay} auf dem FX-Thread aus; das Ergebnis bricht das ab. */
		Runnable after(Duration delay, Runnable action);
	}

	private final ObjectProperty<Entry> current = new SimpleObjectProperty<>();
	private final List<WeakReference<StatusLabel>> labels = new ArrayList<>();
	private final BooleanSupplier footerShown;
	private final Delay delay;
	private Runnable cancelAutoClear;
	/** Der Fehler hinter einer Meldung des Hintergrunds ({@link #showInFooter}); er steht wieder da, wenn sie ausgeblendet wird. */
	private Entry beneath;

	private StatusMessage() {
		this(null, null);
	}

	/**
	 * Paketsichtbar für Tests.
	 *
	 * @param footerShown ob gerade eine Fußzeile zu sehen ist; {@code null}: die angemeldeten {@link StatusLabel}s
	 * @param delay       das Ausblenden; {@code null}: mit {@link PauseTransition}
	 */
	StatusMessage(BooleanSupplier footerShown, Delay delay) {
		this.footerShown = footerShown != null ? footerShown : this::isAnyLabelDisplayed;
		this.delay = delay != null ? delay : StatusMessage::pause;
	}

	public static StatusMessage getInstance() {
		return INSTANCE;
	}

	public ReadOnlyObjectProperty<Entry> currentProperty() {
		return current;
	}

	/** Zeigt eine einzelne Meldung. */
	public void show(Severity severity, String text) {
		show(new Entry(severity, text, text));
	}

	/** Rückmeldung nach erfolgreichem Speichern. */
	public void showSaved() {
		show(Severity.SUCCESS, "Gespeichert");
	}

	/** Zeigt das Ergebnis einer Plausibilitätsprüfung (wichtigste Meldung + "(+n weitere)"); leer = nichts tun. */
	public void show(ValidationResult result) {
		show(result, "");
	}

	/** Wie {@link #show(ValidationResult)}, mit einem Text vor der wichtigsten Meldung (z. B. "Nicht gespeichert: "). */
	public void show(ValidationResult result, String prefix) {
		if (result.isEmpty()) {
			return;
		}
		show(describe(result, prefix));
	}

	/**
	 * Rückmeldung nach einem Speicherversuch mit Prüfung ({@code EventSaver}, {@code HallSaver}): bei Fehlern
	 * "Nicht gespeichert: ...", sonst "Gespeichert", bei Warnungen "Gespeichert mit Warnungen: ...".
	 */
	public void showSaveResult(boolean saved, ValidationResult validation) {
		if (!saved) {
			show(validation, "Nicht gespeichert: ");
		} else if (validation.isEmpty()) {
			showSaved();
		} else {
			show(validation, "Gespeichert mit Warnungen: ");
		}
	}

	/**
	 * Zeigt das Ergebnis der Live-Prüfung wie {@link #show(ValidationResult)}, öffnet aber nie einen Dialog: Ist
	 * gerade keine Fußzeile sichtbar, wird nichts angezeigt (eine Komfortmeldung ist keinen Dialog wert).
	 */
	public void showLive(ValidationResult result) {
		if (result.isEmpty() || !footerShown.getAsBoolean()) {
			return;
		}
		show(describe(result, ""));
	}

	/**
	 * Zeigt einen Text nur in der Fußzeile, öffnet aber nie einen Dialog: Ist gerade keine Fußzeile sichtbar (etwa
	 * während des Starts, bevor das Hauptfenster steht), wird nichts angezeigt. Für Hinweise, die nicht wichtig genug sind, die Arbeit zu unterbrechen, und
	 * die sich häufen können (etwa "Wieder verbunden" bei jeder Rückkehr).
	 * <p>
	 * Steht ein Fehler (etwa "Nicht gespeichert: …"), blendet ein Text mit Info oder Warnung nur kurz darüber; danach steht
	 * der Fehler wieder da. Ein Fehler ersetzt ihn wie jede andere Meldung.
	 */
	public void showInFooter(Severity severity, String text) {
		if (!footerShown.getAsBoolean()) {
			return;
		}
		Entry behind = severity == Severity.ERROR ? null : errorBehind();
		show(new Entry(severity, text, text));
		if (behind != null) {
			beneath = behind;
			startAutoClear();
		}
	}

	/** Der Fehler, zu dem die Fußzeile nach einer Meldung des Hintergrunds zurückkehrt; {@code null}, wenn keiner steht. */
	private Entry errorBehind() {
		if (beneath != null) {
			return beneath;
		}
		Entry shown = current.get();
		return shown != null && shown.severity() == Severity.ERROR ? shown : null;
	}

	/**
	 * Entfernt eine stehende Fehler- oder Warnmeldung, weil nichts mehr zu beanstanden ist (auch die eines
	 * gescheiterten Speicherversuchs). Info und Erfolgsmeldungen ("Gespeichert") bleiben, ein Fehler dahinter kehrt nicht
	 * zurück.
	 */
	public void clearIssues() {
		Entry entry = current.get();
		if (entry != null && (entry.severity() == Severity.ERROR || entry.severity() == Severity.WARNING)) {
			clear();
		}
		beneath = null;
	}

	/**
	 * Entfernt die aktuelle Meldung, wenn ihr Text nach {@code stale} nicht mehr gilt (etwa eine Störung, die vorbei ist);
	 * eine andere Meldung bleibt. Ein hinfälliger Fehler hinter einer Meldung des Hintergrunds kehrt nicht zurück.
	 */
	public void clearIf(Predicate<String> stale) {
		if (beneath != null && stale.test(beneath.text())) {
			beneath = null;
		}
		Entry entry = current.get();
		if (entry != null && stale.test(entry.text())) {
			stopAutoClear();
			current.set(beneath);
			beneath = null;
		}
	}

	/** Entfernt die aktuelle Meldung, z. B. beim Wechsel des Screens oder der Ansicht. */
	public void clear() {
		stopAutoClear();
		beneath = null;
		current.set(null);
	}

	/** Meldung für die Fußzeile aus einem Prüfergebnis bilden (paketsichtbar für Tests). */
	static Entry describe(ValidationResult result) {
		return describe(result, "");
	}

	/**
	 * Meldung aus einem Prüfergebnis: wichtigste Meldung mit Präfix und "(+n weitere)", der Tooltip listet
	 * die Meldungen (höchstens {@value #MAX_DETAIL_LINES} Zeilen, danach "... und n weitere").
	 */
	static Entry describe(ValidationResult result, String prefix) {
		List<ValidationIssue> issues = result.getIssues();
		ValidationIssue first = issues.get(0);
		String text = prefix + first.toDisplayText();
		if (issues.size() > 1) {
			text += " (+" + (issues.size() - 1) + " weitere)";
		}
		List<Line> lines = new ArrayList<>();
		for (ValidationIssue issue : issues.subList(0, Math.min(issues.size(), MAX_DETAIL_LINES))) {
			lines.add(new Line(issue.severity(), issue.toDisplayText()));
		}
		if (issues.size() > MAX_DETAIL_LINES) {
			lines.add(new Line(Severity.INFO, "... und " + (issues.size() - MAX_DETAIL_LINES) + " weitere"));
		}
		String details = lines.stream().map(Line::text).collect(Collectors.joining("\n"));
		return new Entry(first.severity(), text, details, List.copyOf(lines));
	}

	/** Meldet ein Label an, damit {@link #show} weiß, ob die Fußzeile gerade sichtbar ist. */
	void register(StatusLabel label) {
		labels.removeIf(ref -> ref.get() == null);
		labels.add(new WeakReference<>(label));
	}

	/** Zeigt die Meldung; sie ersetzt die aktuelle endgültig, auch einen Fehler hinter einer Meldung des Hintergrunds. */
	private void show(Entry entry) {
		stopAutoClear();
		beneath = null;
		if (!footerShown.getAsBoolean()) {
			showDialog(entry);
			return;
		}
		current.set(entry);
		if (entry.severity() == Severity.INFO || entry.severity() == Severity.SUCCESS) {
			startAutoClear();
		}
	}

	private boolean isAnyLabelDisplayed() {
		return labels.stream().map(WeakReference::get).anyMatch(label -> label != null && label.isDisplayed());
	}

	/** Blendet die aktuelle Meldung nach einigen Sekunden aus; ein Fehler dahinter steht dann wieder da. */
	private void startAutoClear() {
		stopAutoClear();
		cancelAutoClear = delay.after(AUTO_CLEAR_AFTER, () -> {
			cancelAutoClear = null;
			current.set(beneath);
			beneath = null;
		});
	}

	private void stopAutoClear() {
		if (cancelAutoClear != null) {
			cancelAutoClear.run();
			cancelAutoClear = null;
		}
	}

	private static Runnable pause(Duration delay, Runnable action) {
		PauseTransition pause = new PauseTransition(delay);
		pause.setOnFinished(event -> action.run());
		pause.play();
		return pause::stop;
	}

	private static void showDialog(Entry entry) {
		boolean isError = entry.severity() == Severity.ERROR;
		AlertType type = switch (entry.severity()) {
		case ERROR -> AlertType.ERROR;
		case WARNING -> AlertType.WARNING;
		default -> AlertType.INFORMATION;
		};
		MainApp.showAlert(isError ? "Fehler" : "Hinweis", entry.details(), type);
	}
}
