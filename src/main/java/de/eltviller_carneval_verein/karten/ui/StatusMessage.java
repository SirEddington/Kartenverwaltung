package de.eltviller_carneval_verein.karten.ui;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
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
 * der Screen wechselt ({@link #clear()}); Info und Erfolg verschwinden nach einigen Sekunden.
 *
 * Zeigt gerade kein Screen ein {@link StatusLabel}, wird die Meldung stattdessen als Dialog
 * ausgegeben, damit sie nicht verloren geht.
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

	private final ObjectProperty<Entry> current = new SimpleObjectProperty<>();
	private final List<WeakReference<StatusLabel>> labels = new ArrayList<>();
	private PauseTransition autoClear;

	private StatusMessage() {
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
	 * Zeigt das Ergebnis der Live-Prüfung wie {@link #show(ValidationResult)}, öffnet aber nie einen Dialog: Ist
	 * gerade keine Fußzeile sichtbar, wird nichts angezeigt (eine Komfortmeldung ist keinen Dialog wert).
	 */
	public void showLive(ValidationResult result) {
		if (result.isEmpty() || !isAnyLabelDisplayed()) {
			return;
		}
		show(describe(result, ""));
	}

	/**
	 * Entfernt eine stehende Fehler- oder Warnmeldung, weil nichts mehr zu beanstanden ist (auch die eines
	 * gescheiterten Speicherversuchs). Info und Erfolgsmeldungen ("Gespeichert") bleiben.
	 */
	public void clearIssues() {
		Entry entry = current.get();
		if (entry != null && (entry.severity() == Severity.ERROR || entry.severity() == Severity.WARNING)) {
			clear();
		}
	}

	/** Entfernt die aktuelle Meldung, z. B. beim Wechsel des Screens oder der Ansicht. */
	public void clear() {
		stopAutoClear();
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

	private void show(Entry entry) {
		stopAutoClear();
		if (!isAnyLabelDisplayed()) {
			showDialog(entry);
			return;
		}
		current.set(entry);
		if (entry.severity() == Severity.INFO || entry.severity() == Severity.SUCCESS) {
			autoClear = new PauseTransition(AUTO_CLEAR_AFTER);
			autoClear.setOnFinished(event -> current.set(null));
			autoClear.play();
		}
	}

	private boolean isAnyLabelDisplayed() {
		return labels.stream().map(WeakReference::get).anyMatch(label -> label != null && label.isDisplayed());
	}

	private void stopAutoClear() {
		if (autoClear != null) {
			autoClear.stop();
			autoClear = null;
		}
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
