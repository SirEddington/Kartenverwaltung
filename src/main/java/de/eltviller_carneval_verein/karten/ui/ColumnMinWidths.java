package de.eltviller_carneval_verein.karten.ui;

import java.util.List;
import java.util.Map;

import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TableView.ResizeFeatures;
import javafx.scene.control.skin.TableColumnHeader;
import javafx.scene.control.skin.TableHeaderRow;
import javafx.util.Callback;

/**
 * Mindestbreiten für Tabellenspalten (KARV-126): Eine Spalte lässt sich nicht schmaler ziehen, als ihre Überschrift samt
 * Sortierpfeil braucht. Spalten mit einer Untergrenze für den Inhalt (etwa Namen) bleiben mindestens so breit. Ist
 * die Tabelle schmaler als alle Mindestbreiten zusammen, erscheint ein waagerechter Rollbalken.
 * <p>
 * Die Überschrift wird so gemessen, wie sie erscheint (Schrift aus dem Stylesheet). Messbar ist sie erst nach dem
 * ersten CSS-Durchlauf; deshalb rechnet die Spalte neu, sobald sich am Label des Spaltenkopfs etwas ändert.
 */
public final class ColumnMinWidths {

	/** Platz für den Sortierpfeil neben der Überschrift; er erscheint erst beim Sortieren und soll dann nichts abschneiden. */
	static final double SORT_ARROW_SPACE = 16;
	/** Platz, den der senkrechte Rollbalken von der Tabellenbreite abzieht, sobald die Zeilen nicht mehr hineinpassen. */
	static final double SCROLL_BAR_SPACE = 20;

	private ColumnMinWidths() {
	}

	/**
	 * Setzt die Mindestbreiten, sobald die Tabelle ihre Spaltenköpfe hat.
	 *
	 * @param contentFloors Untergrenzen für einzelne Spalten, die über der Breite der Überschrift liegen dürfen
	 */
	public static void install(TableView<?> table, Map<TableColumn<?, ?>, Double> contentFloors) {
		fitOrScroll(table);
		table.skinProperty().addListener((obs, old, skin) -> {
			if (skin != null) {
				watchHeaders(table, contentFloors);
			}
		});
		if (table.getSkin() != null) {
			watchHeaders(table, contentFloors);
		}
	}

	/**
	 * Die verteilende Spaltenregel (aus dem FXML) gibt auf, wenn die Mindestbreiten zusammen breiter sind als die Tabelle:
	 * Die letzten Spalten ragen dann ohne Rollbalken hinaus. In dem Fall gilt die freie Regel, die einen waagerechten
	 * Rollbalken zeigt; wird die Tabelle wieder breit genug, verteilt die ursprüngliche Regel die Breite neu.
	 */
	private static void fitOrScroll(TableView<?> table) {
		Callback<ResizeFeatures, Boolean> fitting = table.getColumnResizePolicy();
		InvalidationListener choose = o -> table.setColumnResizePolicy(
				minWidthsFit(table) ? fitting : TableView.UNCONSTRAINED_RESIZE_POLICY);
		table.widthProperty().addListener(choose);
		for (TableColumn<?, ?> column : table.getColumns()) {
			column.minWidthProperty().addListener(choose);
			column.visibleProperty().addListener(choose);
		}
	}

	private static boolean minWidthsFit(TableView<?> table) {
		double minWidths = 0;
		for (TableColumn<?, ?> column : table.getColumns()) {
			if (column.isVisible()) {
				minWidths += column.getMinWidth();
			}
		}
		return minWidths <= table.getWidth() - table.snappedLeftInset() - table.snappedRightInset() - SCROLL_BAR_SPACE;
	}

	/** Die Kopfzeile legt ihre Spaltenköpfe erst beim ersten Layout an (und neu, wenn Spalten wechseln). */
	private static void watchHeaders(TableView<?> table, Map<TableColumn<?, ?>, Double> contentFloors) {
		if (!(table.lookup(".column-header-background") instanceof TableHeaderRow row)) {
			return;
		}
		ObservableList<TableColumnHeader> headers = row.getRootHeader().getColumnHeaders();
		headers.addListener((ListChangeListener<TableColumnHeader>) change -> {
			while (change.next()) {
				change.getAddedSubList().forEach(header -> watch(header, contentFloors));
			}
		});
		headers.forEach(header -> watch(header, contentFloors));
	}

	private static void watch(TableColumnHeader header, Map<TableColumn<?, ?>, Double> contentFloors) {
		if (!(header.lookup(".label") instanceof Label label)) {
			return;
		}
		TableColumnBase<?, ?> column = header.getTableColumn();
		double floor = contentFloors.getOrDefault(column, 0.0);
		InvalidationListener update = o -> column.setMinWidth(Math.max(floor, label.prefWidth(-1)
				+ header.snappedLeftInset() + header.snappedRightInset() + SORT_ARROW_SPACE));
		// Das Stylesheet setzt die Schrift, bevor das Label seine Skin hat; erst mit ihr lässt es sich messen
		for (Observable measure : List.of(label.skinProperty(), label.fontProperty(), label.textProperty(), label.graphicProperty())) {
			measure.addListener(update);
		}
		update.invalidated(null);
	}
}
