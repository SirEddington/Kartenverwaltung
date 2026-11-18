package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Collection;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.ui.CoalescedAction;
import de.eltviller_carneval_verein.karten.ui.LiveViews;
import de.eltviller_carneval_verein.karten.ui.PopupWindows;
import de.eltviller_carneval_verein.karten.util.BookingTotals;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableSet;
import javafx.collections.SetChangeListener;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Window;

/**
 * Anzahl und Preis der gewählten Karten am Mauszeiger, über Saalansicht und Kartentabelle der Verkaufsansicht
 * (KARV-194): „5 Karten gewählt · 60,00 €". Die Shell legt den Hinweis über ihren Inhaltsbereich; er ist durchlässig für
 * die Maus und liest die gemeinsame Auswahl ({@link SalesViewState#selectedBookings()}), die Ansichten wissen nichts von
 * ihm. Zu sehen ist er nur, solange der Zeiger über dem Inhaltsbereich ist, das Hauptfenster den Fokus hat und kein
 * Dialog oder Menü des Fensters offen ist ({@link Conditions}). Er steht rechts über oder rechts unter dem Zeiger, wie im
 * Menü "Anzeige" gewählt (KARV-197, {@link #place}).
 * <p>
 * Nur im JavaFX-Thread verwenden.
 */
final class SelectionHint {

	/** Abstand der linken Kante rechts vom Zeiger und der rechten Kante links vom Zeiger. */
	private static final double GAP_RIGHT = 16;
	private static final double GAP_LEFT = 8;
	/** Abstand der unteren Kante über dem Zeiger und der oberen Kante unter dem Zeiger (Platz für die Zeigerspitze). */
	private static final double GAP_ABOVE = 8;
	private static final double GAP_BELOW = 20;

	/**
	 * Wann der Hinweis zu sehen ist.
	 *
	 * @param enabled       im Untermenü „Auswahl am Mauszeiger" (Menü „Anzeige") ist nicht „Aus" gewählt
	 * @param hasSelection  mindestens eine Karte gewählt
	 * @param pointerInside der Zeiger ist über dem Inhaltsbereich
	 * @param windowFocused das Hauptfenster hat den Fokus (ein modaler Dialog nimmt ihn)
	 * @param popupOpen     ein Sitz-Dialog, Kontextmenü oder eine Auswahlliste des Fensters ist offen ({@link #blocks})
	 */
	record Conditions(boolean enabled, boolean hasSelection, boolean pointerInside, boolean windowFocused, boolean popupOpen) {
		boolean visible() {
			return enabled && hasSelection && pointerInside && windowFocused && !popupOpen;
		}
	}

	private final Region area;
	private final ObservableSet<Booking> selection;
	private final ObservableBooleanValue enabled;
	private final ObservableValue<SelectionHintPlacement> placement;
	private final ObservableBooleanValue avoidEdges;
	private final Label label = new Label();
	/** Der Zeiger in Koordinaten von {@link #area}; {@code null}, solange er nicht darüber ist. */
	private Point2D pointer;
	/** Das Hauptfenster, solange die Shell zu sehen ist. */
	private Window window;
	/** Ob ein Popup des Hauptfensters offen ist; neu bestimmt, wenn sich die Fensterliste ändert, nicht je Mausbewegung. */
	private boolean popupOpen;

	/**
	 * @param area      der Inhaltsbereich der Shell; der Hinweis liegt als nicht angeordnetes Kind darin ({@link #node()})
	 * @param selection die gemeinsame Auswahl der Verkaufsansicht
	 * @param enabled   der Schalter im Menü „Anzeige" (app-weit, wird nur schwach beobachtet)
	 * @param placement  die Lage aus demselben Menü ({@code null} gilt als {@link SelectionHintPlacement#ABOVE}; app-weit,
	 *                   wird nur schwach beobachtet)
	 * @param avoidEdges der Haken „Am Rand ausweichen" aus demselben Menü (app-weit, wird nur schwach beobachtet)
	 */
	SelectionHint(Region area, ObservableSet<Booking> selection, ObservableBooleanValue enabled,
			ObservableValue<SelectionHintPlacement> placement, ObservableBooleanValue avoidEdges) {
		this.area = area;
		this.selection = selection;
		this.enabled = enabled;
		this.placement = placement;
		this.avoidEdges = avoidEdges;
		label.getStyleClass().add("selection-hint");
		label.setManaged(false);
		label.setMouseTransparent(true);
		label.setVisible(false);

		// Filter statt Handler: Saalplan und Tabelle verbrauchen ihre Mausereignisse selbst
		area.addEventFilter(MouseEvent.MOUSE_MOVED, this::track);
		area.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::track);
		// Nur das Verlassen des Bereichs selbst, nicht das eines Kindes (das käme als MOUSE_EXITED_TARGET)
		area.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
			pointer = null;
			update();
		});
		// Ein Rahmen über hundert Sitze meldet hundert Änderungen: einmal je Takt auswerten
		CoalescedAction selectionChanged = new CoalescedAction(Platform::runLater, this::update);
		selection.addListener((SetChangeListener<Booking>) change -> selectionChanged.request());
		LiveViews.follow(area, enabled, this::update);
		LiveViews.follow(area, placement, this::update);
		LiveViews.follow(area, avoidEdges, this::update);
		// Die Fensterliste ist statisch, das Hauptfenster überlebt die Shell: Beides nur, solange sie zu sehen ist
		ChangeListener<Boolean> focusChanged = (obs, wasFocused, isFocused) -> update();
		ListChangeListener<Window> windowsChanged = change -> updatePopups();
		LiveViews.whileShown(area, () -> {
			window = area.getScene().getWindow();
			window.focusedProperty().addListener(focusChanged);
			Window.getWindows().addListener(windowsChanged);
			updatePopups();
		}, () -> {
			Window.getWindows().removeListener(windowsChanged);
			if (window != null) {
				window.focusedProperty().removeListener(focusChanged);
			}
			window = null;
			updatePopups();
		});
	}

	/** Der Hinweis; die Shell legt ihn als letztes Kind in den Inhaltsbereich, über die Ansicht. */
	Node node() {
		return label;
	}

	/** „1 Karte gewählt · 12,00 €": Anzahl wie im Kontextmenü (alle gewählten Buchungen), Preis wie im Kundenfenster. */
	static String text(Collection<Booking> selected) {
		return SelectionSummary.countText(selected.size()) + " gewählt · " + MoneyFormat.formatCents(BookingTotals.of(selected).potentialCents());
	}

	/**
	 * Die linke obere Ecke des Hinweises: rechts vom Zeiger und über bzw. unter ihm, wie gewählt. Mit
	 * {@code avoidEdges} wechselt er die Seite, wo er sonst hinausragte (rechts nach links, oben nach unten und umgekehrt);
	 * ohne behält er seine Seite und wird nur an den Rand geschoben, auch über den Zeiger (KARV-197). Bleibt im Bereich;
	 * ist der Bereich kleiner als der Hinweis, beginnt er links bzw. oben am Rand.
	 */
	static Point2D place(SelectionHintPlacement placement, boolean avoidEdges, double pointerX, double pointerY, double width,
			double height, double areaWidth, double areaHeight) {
		double right = pointerX + GAP_RIGHT;
		double above = pointerY - GAP_ABOVE - height;
		double below = pointerY + GAP_BELOW;
		boolean left = avoidEdges && right + width > areaWidth;
		boolean under = placement == SelectionHintPlacement.BELOW;
		if (avoidEdges && (under ? below + height > areaHeight : above < 0)) {
			under = !under;
		}
		double x = left ? pointerX - GAP_LEFT - width : right;
		double y = under ? below : above;
		return new Point2D(clamp(x, areaWidth - width), clamp(y, areaHeight - height));
	}

	/** {@code value} zwischen 0 und {@code max}; ist {@code max} negativ (Bereich kleiner als der Hinweis), 0. */
	private static double clamp(double value, double max) {
		return Math.max(0, Math.min(value, max));
	}

	/**
	 * Ob {@code candidate} den Hinweis verdeckt: ein offenes Popup (Sitz-Dialog, Kontextmenü, Auswahlliste,
	 * Datumsauswahl), das letztlich zu {@code main} gehört. Tooltips nicht: Sie verdecken nichts, und ein Tooltip, der beim
	 * Hineinfahren in den Inhaltsbereich noch kurz offen ist, soll den Hinweis nicht ausblenden.
	 */
	static boolean blocks(Window candidate, Window main) {
		return !(candidate instanceof Tooltip) && PopupWindows.belongsTo(candidate, main);
	}

	/** {@link Window#getWindows()} enthält nur gezeigte Fenster. */
	private void updatePopups() {
		popupOpen = window != null && Window.getWindows().stream().anyMatch(candidate -> blocks(candidate, window));
		update();
	}

	private void track(MouseEvent event) {
		Point2D local = area.sceneToLocal(event.getSceneX(), event.getSceneY());
		// Beim Ziehen mit gedrückter Taste kommt MOUSE_DRAGGED auch, wenn der Zeiger schon außerhalb ist
		pointer = local != null && area.getLayoutBounds().contains(local) ? local : null;
		update();
	}

	private void update() {
		boolean visible = new Conditions(enabled.get(), !selection.isEmpty(), pointer != null, window != null && window.isFocused(), popupOpen)
				.visible();
		if (visible) {
			// Bei jeder Auswertung neu: Ändert ein anderer Rechner einen Preis, folgt der Betrag mit der nächsten Mausbewegung
			String text = text(selection);
			if (!text.equals(label.getText()) || !label.isVisible()) {
				label.setText(text);
				// Nicht angeordnete Kinder legt der StackPane nicht aus: Größe selbst bestimmen, bevor sie in die Lage eingeht
				label.applyCss();
				label.autosize();
			}
			Point2D corner = place(placement.getValue(), avoidEdges.get(), pointer.getX(), pointer.getY(), label.getWidth(),
					label.getHeight(), area.getWidth(), area.getHeight());
			label.relocate(corner.getX(), corner.getY());
		}
		label.setVisible(visible);
	}
}
