package de.eltviller_carneval_verein.karten.ui.input;

import java.util.Map;
import java.util.WeakHashMap;

import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.validation.ValidationIssue;
import de.eltviller_carneval_verein.karten.validation.ValidationResult;
import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * Rückmeldung bei einer nicht übernommenen Eingabe, für Tabellenzellen und Spinner gleich: Die Meldung (mit Code)
 * steht in der Fußzeile, und die Eingabestelle wird für kurze Zeit rot markiert ({@value #ERROR_STYLE_CLASS}, siehe
 * {@code style.css}). Nur im JavaFX-Thread aufrufen.
 */
public final class InputFeedback {

	/** CSS-Klasse der kurzen roten Markierung. */
	public static final String ERROR_STYLE_CLASS = "input-error";

	private static final Duration MARK_DURATION = Duration.millis(1500);

	// Je Knoten die laufende Markierung: Ein neuer Fehler startet die Zeit neu, die Klasse wird nie doppelt gesetzt
	private static final Map<Node, PauseTransition> MARKS = new WeakHashMap<>();

	private InputFeedback() {
	}

	/** Bezeichnung für die Meldung, z. B. "Preis von Tisch 4, Sitz 2"; ohne Zeilenbezeichnung nur der Feldname. */
	public static String label(String fieldName, String rowLabel) {
		return (rowLabel == null || rowLabel.isBlank()) ? fieldName : fieldName + " von " + rowLabel;
	}

	/** Der getippte Text für die Meldung; leer oder nur Leerzeichen heißt "(leer)". */
	public static String displayText(String text) {
		return (text == null || text.isBlank()) ? "(leer)" : text;
	}

	/** Zeigt die Meldung in der Fußzeile und markiert den Knoten (darf {@code null} sein) kurz rot. */
	public static void report(ValidationIssue issue, Node node) {
		StatusMessage.getInstance().show(ValidationResult.of(issue));
		mark(node);
	}

	private static void mark(Node node) {
		if (node == null) {
			return;
		}
		PauseTransition running = MARKS.remove(node);
		if (running != null) {
			running.stop();
		}
		if (!node.getStyleClass().contains(ERROR_STYLE_CLASS)) {
			node.getStyleClass().add(ERROR_STYLE_CLASS);
		}
		PauseTransition pause = new PauseTransition(MARK_DURATION);
		pause.setOnFinished(event -> {
			node.getStyleClass().remove(ERROR_STYLE_CLASS);
			MARKS.remove(node);
		});
		MARKS.put(node, pause);
		pause.play();
	}
}
