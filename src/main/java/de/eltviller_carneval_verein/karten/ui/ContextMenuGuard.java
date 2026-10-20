package de.eltviller_carneval_verein.karten.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.ListChangeListener;
import javafx.geometry.Point2D;
import javafx.scene.control.ContextMenu;
import javafx.scene.robot.Robot;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Schließt jedes Kontextmenü der Anwendung, sobald die Maus weit genug davon entfernt ist. JavaFX schließt ein Menü nur
 * bei einem Klick daneben oder beim Wechsel des Fensters; wer die Maus einfach wegbewegt, ließ es stehen. Die Wache
 * meldet sich einmal für alle Fenster an und beobachtet jedes Menü, solange es offen ist (auch Untermenüs). Gemessen wird
 * von der Mausposition auf dem Bildschirm bis zum Rand des Menüs, auch wenn die Maus das Fenster verlassen hat.
 *
 * <p>
 * Ein Menü, das mit der Tastatur geöffnet wird, während die Maus weit weg steht, schließt nicht sofort: Die Wache wird erst
 * scharf, wenn die Maus einmal in der Nähe war.
 */
public final class ContextMenuGuard {

	/** Abstand in Pixeln von der Menüfläche, ab dem das Menü schließt. */
	static final double MAX_DISTANCE = 120;

	private static final Duration POLL_INTERVAL = Duration.millis(100);

	private static Robot robot;
	private static boolean installed;

	private ContextMenuGuard() {
	}

	/** Meldet die Wache an (einmal, vom JavaFX-Thread aus). Weitere Aufrufe tun nichts. */
	public static void install() {
		if (installed) {
			return;
		}
		installed = true;
		robot = new Robot();
		Window.getWindows().addListener((ListChangeListener<Window>) change -> {
			while (change.next()) {
				if (change.wasAdded()) {
					for (Window window : change.getAddedSubList()) {
						if (window instanceof ContextMenu menu) {
							watch(menu);
						}
					}
				}
			}
		});
	}

	/** Abstand eines Punktes zu einem Rechteck (0 innerhalb und auf dem Rand). */
	static double distance(double pointX, double pointY, double x, double y, double width, double height) {
		double dx = Math.max(Math.max(x - pointX, 0), pointX - (x + width));
		double dy = Math.max(Math.max(y - pointY, 0), pointY - (y + height));
		return Math.hypot(dx, dy);
	}

	static boolean isTooFar(double distance) {
		return distance > MAX_DISTANCE;
	}

	private static void watch(ContextMenu menu) {
		boolean[] armed = { false }; // Erst scharf, wenn die Maus einmal in der Nähe war
		Timeline poll = new Timeline();
		poll.setCycleCount(Animation.INDEFINITE);
		poll.getKeyFrames().add(new KeyFrame(POLL_INTERVAL, event -> {
			if (!menu.isShowing()) {
				poll.stop();
				return;
			}
			Point2D mouse = robot.getMousePosition();
			boolean tooFar = isTooFar(distance(mouse.getX(), mouse.getY(), menu.getX(), menu.getY(), menu.getWidth(), menu.getHeight()));
			if (!tooFar) {
				armed[0] = true;
			} else if (armed[0]) {
				poll.stop();
				menu.hide();
			}
		}));
		poll.play();
	}
}
