package de.eltviller_carneval_verein.karten.ui.input;

import java.util.function.IntConsumer;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Control;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.skin.VirtualFlow;

/**
 * Hilfen für die Bedienung einer Tabelle mit der Maus und beim Scrollen, gemeinsam für {@link TableInput} und die
 * Kartentabelle ({@code TicketTreeTable}): Welche Zeile, Zelle oder welches Bedienelement ein Ereignis trifft, ab wann
 * ein Druck ein Ziehen ist, Scrollen nur so weit wie nötig und das kurze Sperren des Bearbeitens. Wann was gilt,
 * entscheiden die Aufrufer.
 */
public final class TableGestures {

	/** Ab dieser Strecke (in Pixeln) ist ein Druck ein Ziehen. */
	public static final double DRAG_THRESHOLD = 4;

	private TableGestures() {
	}

	/** Der erste Knoten ab dem Ereignisziel aufwärts (es selbst eingeschlossen), der {@code wanted} erfüllt, sonst {@code null}. */
	public static Node ancestor(Object target, Predicate<Node> wanted) {
		for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent()) {
			if (wanted.test(node)) {
				return node;
			}
		}
		return null;
	}

	/** Die Zeile, in der das Ereignisziel liegt; {@code null} außerhalb der Zeilen (auch auf einer Bildlaufleiste). */
	public static Node rowOf(Object target, Predicate<Node> isRow) {
		Node found = ancestor(target, node -> isRow.test(node) || node instanceof ScrollBar);
		return found instanceof ScrollBar ? null : found;
	}

	/**
	 * Ob das Ereignisziel in einer Checkbox, Auswahlliste oder einem Textfeld liegt, gesucht bis zur ersten Zeile
	 * ({@code isRow}): Ein solches Bedienelement behält den Druck für sich.
	 */
	public static boolean insideControl(Object target, Predicate<Node> isRow) {
		Node found = ancestor(target,
				node -> node instanceof ButtonBase || node instanceof ComboBoxBase<?> || node instanceof TextInputControl || isRow.test(node));
		return found != null && !isRow.test(found);
	}

	/** Der {@code VirtualFlow} der Tabelle, {@code null}: keiner (noch ohne Skin). */
	public static VirtualFlow<?> flowOf(Control table) {
		return table.lookup(".virtual-flow") instanceof VirtualFlow<?> flow ? flow : null;
	}

	/**
	 * Scrollt nur so weit, dass die Zeile ganz zu sehen ist: {@code scrollTo} der Tabelle setzte sie an den oberen Rand.
	 * Ohne {@code VirtualFlow} gilt {@code fallback} (das {@code scrollTo} der Tabelle).
	 */
	public static void scrollIntoView(Control table, int row, IntConsumer fallback) {
		VirtualFlow<?> flow = flowOf(table);
		if (flow != null) {
			flow.scrollTo(row);
		} else {
			fallback.accept(row);
		}
	}

	/**
	 * Die Tabelle ist für die Dauer des laufenden Ereignisses nicht bearbeitbar: Die Zelle fragt beim Start des
	 * Bearbeitens danach. {@code editable} ist die Property „bearbeitbar“ der Tabelle; sie ist beim Aufruf wahr. Danach
	 * wird sie nur wieder wahr, wenn sie während der Sperre niemand sonst geändert hat (etwa ein Wechsel in den
	 * Anzeigemodus): Was von außen gesetzt wurde, gilt.
	 */
	public static void suspendEditing(BooleanProperty editable) {
		boolean[] changedOutside = { false };
		// ein ChangeListener, kein InvalidationListener: Er liest den Wert und erfährt so jede weitere Änderung
		ChangeListener<Boolean> outside = (observable, before, now) -> changedOutside[0] = true;
		editable.set(false);
		editable.addListener(outside);
		// Das Ereignis ist verarbeitet, sobald es durch ist
		Platform.runLater(() -> {
			editable.removeListener(outside);
			if (!changedOutside[0]) {
				editable.set(true);
			}
		});
	}
}
