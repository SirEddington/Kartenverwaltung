package de.eltviller_carneval_verein.karten.ui;

import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.ForeignBatch;
import de.eltviller_carneval_verein.karten.repository.RepositoryListener;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitType;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Window;

/**
 * Hilfen für Ansichten, die fremde Änderungen anzeigen: Sie melden sich bei den Repositories an, solange sie zu sehen
 * sind, und wieder ab, sobald sie es nicht mehr sind. So hält kein Repository eine verworfene Ansicht am Leben. Außerdem
 * bündeln sie die fremden Änderungen zu einer Auffrischung je Takt ({@link #coalescing}).
 */
public final class LiveViews {

	private LiveViews() {
	}

	/**
	 * Ruft {@code register} auf, sobald die Szene von {@code anchor} in einem gezeigten Fenster steht, und {@code unregister},
	 * sobald das nicht mehr gilt (Ansicht aus der Szene genommen, Szene ersetzt, Fenster geschlossen). Beides wechselt sich
	 * ab, jeweils höchstens einmal je Wechsel; steht {@code anchor} schon in einem gezeigten Fenster, läuft {@code register}
	 * sofort. Alles auf dem FX-Thread.
	 */
	public static void whileShown(Node anchor, Runnable register, Runnable unregister) {
		new Watch(anchor, register, unregister).update();
	}

	/**
	 * Wie {@link #whileShown(Node, Runnable, Runnable)}: Die Registrierungen werden der Reihe nach an- und abgemeldet.
	 */
	public static void whileShown(Node anchor, Registration... registrations) {
		whileShown(anchor, () -> {
			for (Registration registration : registrations) {
				registration.attach().run();
			}
		}, () -> {
			for (Registration registration : registrations) {
				registration.detach().run();
			}
		});
	}

	/** Jede Einheit ist für die Ansicht von Belang (Abrechnung, Statistik, Kopf-Boxen der Shells). */
	public static final Predicate<Set<UnitKey>> ALL_UNITS = units -> true;

	/**
	 * Ein Listener, der fremde Änderungen sammelt und die Ansicht einmal je Takt auffrischt: Alle Rückrufe bis zum nächsten
	 * Lauf von {@code later} (in der App {@code Platform::runLater}) ergeben höchstens ein {@code refresh}, und nur wenn
	 * mindestens einer von Belang ist. Bei {@code changedByOthers} entscheiden die gemeldeten Einheiten
	 * ({@link ForeignBatch#units()}), ein fremd angelegter oder gelöschter Eintrag ist immer von Belang. Alles auf dem FX-Thread.
	 *
	 * @param later    plant einen Lauf für später ein
	 * @param relevant ob unter den gemeldeten Einheiten eine für die Ansicht von Belang ist
	 * @param refresh  führt die Ansicht nach
	 */
	public static <T> RepositoryListener<T> coalescing(Executor later, Predicate<Set<UnitKey>> relevant, Runnable refresh) {
		CoalescedAction tick = new CoalescedAction(later::execute, refresh);
		return new RepositoryListener<>() {
			@Override
			public void changedByOthers(T entity, ForeignBatch<T> batch) {
				if (relevant.test(batch.units())) {
					tick.request();
				}
			}

			@Override
			public void addedByOthers(T entity) {
				tick.request();
			}

			@Override
			public void removedByOthers(T entity) {
				tick.request();
			}
		};
	}

	/** Wie {@link #coalescing}, mit dem Lauf auf dem FX-Thread ({@code Platform::runLater}), wie in der App. */
	public static <T> RepositoryListener<T> onFxThread(Predicate<Set<UnitKey>> relevant, Runnable refresh) {
		return coalescing(Platform::runLater, relevant, refresh);
	}

	/** Von Belang sind Einheiten einer der Arten (z. B. die Übersichten: nur Event und Vorstellung, keine Buchungen). */
	public static Predicate<Set<UnitKey>> ofTypes(UnitType... types) {
		Set<UnitType> wanted = Set.of(types);
		return units -> units.stream().anyMatch(unit -> wanted.contains(unit.type()));
	}

	/**
	 * Ob unter {@code units} eine zum gezeigten Event und zur gezeigten Vorstellung gehört: ihr Kopf (Namen, Preise), die
	 * Vorstellung selbst oder eine ihrer Buchungen. Ohne Vorstellung ({@code null}: alle) zählt das ganze Event, ohne Event
	 * nichts.
	 */
	public static boolean touches(Set<UnitKey> units, Event event, Presentation presentation) {
		if (event == null) {
			return false;
		}
		for (UnitKey unit : units) {
			if (!event.getId().equals(unit.eventId())) {
				continue;
			}
			if (presentation == null || unit.type() == UnitType.EVENT || presentation.getId().equals(unit.presentationId())) {
				return true;
			}
		}
		return false;
	}

	/** Ob unter {@code units} die Halle mit dieser ID ist ({@code null}: keine Halle gezeigt, nichts von Belang). */
	public static boolean touchesHall(Set<UnitKey> units, String hallId) {
		return hallId != null && units.stream().anyMatch(unit -> unit.type() == UnitType.HALL && hallId.equals(unit.hallId()));
	}

	/** Ob die Ansicht von {@code anchor} in einem Fenster steht (Szene und Fenster sind da). */
	public static boolean isShown(Node anchor) {
		return anchor.getScene() != null && anchor.getScene().getWindow() != null;
	}

	/**
	 * Ein Paar aus An- und Abmelden, das {@link #whileShown(Node, Registration...)} solange gilt, wie die Ansicht zu sehen ist.
	 *
	 * @param attach das Anmelden
	 * @param detach das Abmelden (das Gegenstück zu {@code attach})
	 */
	public record Registration(Runnable attach, Runnable detach) {

		/** Meldet {@code listener} über {@code add} an und über {@code remove} wieder ab (z. B. {@code repository::addListener}). */
		public static <T> Registration of(Consumer<RepositoryListener<T>> add, Consumer<RepositoryListener<T>> remove, RepositoryListener<T> listener) {
			return new Registration(() -> add.accept(listener), () -> remove.accept(listener));
		}

		/** Eine Registrierung, die beim Anmelden nichts tut und nur beim Abmelden {@code detach} ausführt. */
		public static Registration onDetach(Runnable detach) {
			return new Registration(() -> {
			}, detach);
		}
	}

	/** Beobachtet die Kette Knoten, Szene, Fenster, gezeigt; hängt sich um, wenn ein Glied wechselt. */
	private static final class Watch {

		private final Node anchor;
		private final Runnable register;
		private final Runnable unregister;
		private final InvalidationListener changed = observable -> update();
		private Scene scene;
		private Window window;
		private boolean registered;

		Watch(Node anchor, Runnable register, Runnable unregister) {
			this.anchor = anchor;
			this.register = register;
			this.unregister = unregister;
			anchor.sceneProperty().addListener(changed);
		}

		void update() {
			Scene currentScene = anchor.getScene();
			if (currentScene != scene) {
				if (scene != null) {
					scene.windowProperty().removeListener(changed);
				}
				scene = currentScene;
				if (scene != null) {
					scene.windowProperty().addListener(changed);
				}
			}
			Window currentWindow = scene == null ? null : scene.getWindow();
			if (currentWindow != window) {
				if (window != null) {
					window.showingProperty().removeListener(changed);
				}
				window = currentWindow;
				if (window != null) {
					window.showingProperty().addListener(changed);
				}
			}
			boolean shown = window != null && window.isShowing();
			if (shown != registered) {
				registered = shown;
				(shown ? register : unregister).run();
			}
		}
	}
}
