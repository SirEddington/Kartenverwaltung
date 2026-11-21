package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.ui.sales.TicketFilter.FilterValue;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.StringConverter;

/**
 * Der Spaltenfilter der Kartentabelle (KARV-193, Spec Abschnitt 8), Nachbau der Bedienung von ControlsFX: Rechtsklick
 * auf den Spaltenkopf öffnet Suchfeld, Werteliste mit Häkchen und Anzahl und die Knöpfe „Anwenden“, „Keine“, „Alle“ und
 * „Alle Filter zurücksetzen“. Erst „Anwenden“ ändert den {@link TicketFilter}; Schließen ohne „Anwenden“ verwirft die
 * Häkchen, denn jedes Öffnen liest die Werteliste und die Häkchen neu. Eine Spalte mit aktivem Filter zeigt im Kopf ein
 * Filtersymbol.
 */
final class ColumnFilterMenu {

	/** Unter diesem Schlüssel hängt das Menü in den Eigenschaften seiner Spalte (für das Symbol nach dem Zurücksetzen). */
	private static final String PROPERTY = ColumnFilterMenu.class.getName();
	/** Trichter, 10 × 10 px. */
	private static final String FUNNEL = "M0 0 H10 L6 4.5 V10 L4 8.5 V4.5 Z";

	/** Ein Eintrag der Werteliste: der Wert und sein Häkchen. */
	static final class Entry {
		private final FilterValue value;
		private final BooleanProperty checked;
		/** Der Text klein geschrieben, für die Suche. */
		private final String searchText;

		Entry(FilterValue value, boolean checked) {
			this.value = value;
			this.checked = new SimpleBooleanProperty(checked);
			this.searchText = value.text().toLowerCase(Locale.ROOT);
		}

		FilterValue value() {
			return value;
		}

		BooleanProperty checked() {
			return checked;
		}

		/** Text und Anzahl, etwa „Müller (4)“. */
		String label() {
			return value.text() + " (" + value.count() + ")";
		}
	}

	private final TreeTableColumn<TicketRow, ?> column;
	private final TicketColumn key;
	private final TicketFilter filter;
	private final Supplier<List<FilterValue>> values;
	private final Runnable applied;
	private final ContextMenu menu = new ContextMenu();
	private final TextField search = new TextField();
	private final ObservableList<Entry> entries = FXCollections.observableArrayList();
	private final FilteredList<Entry> visible = new FilteredList<>(entries);
	/** Das Filtersymbol im Kopf, solange der Filter aktiv ist. */
	private final SVGPath funnel = new SVGPath();

	private ColumnFilterMenu(TreeTableColumn<TicketRow, ?> column, TicketColumn key, TicketFilter filter, Supplier<List<FilterValue>> values,
			Runnable applied) {
		this.column = column;
		this.key = key;
		this.filter = filter;
		this.values = values;
		this.applied = applied;
	}

	/**
	 * Hängt den Filter als Kontextmenü an den Kopf der Spalte.
	 *
	 * @param values  die Werteliste der Spalte zum Zeitpunkt des Öffnens ({@link TicketFilter#values})
	 * @param applied läuft nur, wenn „Anwenden“ oder „Alle Filter zurücksetzen“ den Filter tatsächlich geändert hat
	 */
	static void install(TreeTableColumn<TicketRow, ?> column, TicketColumn key, TicketFilter filter, Supplier<List<FilterValue>> values,
			Runnable applied) {
		ColumnFilterMenu filterMenu = new ColumnFilterMenu(column, key, filter, values, applied);
		filterMenu.build();
		column.getProperties().put(PROPERTY, filterMenu);
		column.setContextMenu(filterMenu.menu);
		filterMenu.updateSymbol();
	}

	private void build() {
		funnel.setContent(FUNNEL);
		funnel.getStyleClass().add("filter-symbol");
		search.setPromptText("Suchen…");
		search.textProperty().addListener((obs, before, text) -> {
			String needle = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
			visible.setPredicate(entry -> entry.searchText.contains(needle));
		});

		ListView<Entry> list = new ListView<>(visible);
		list.setPrefHeight(220);
		list.setPrefWidth(220);
		list.setCellFactory(CheckBoxListCell.forListView(Entry::checked, new StringConverter<>() {
			@Override
			public String toString(Entry entry) {
				return entry == null ? "" : entry.label();
			}

			@Override
			public Entry fromString(String text) {
				throw new UnsupportedOperationException();
			}
		}));

		Button apply = new Button("Anwenden");
		apply.setDefaultButton(true);
		apply.setOnAction(event -> apply());
		Button none = new Button("Keine");
		none.setOnAction(event -> visible.forEach(entry -> entry.checked().set(false)));
		Button all = new Button("Alle");
		all.setOnAction(event -> visible.forEach(entry -> entry.checked().set(true)));
		Button reset = new Button("Alle Filter zurücksetzen");
		reset.setOnAction(event -> reset());
		reset.setMaxWidth(Double.MAX_VALUE);

		VBox content = new VBox(6, search, list, new HBox(6, apply, none, all), reset);
		content.getStyleClass().add("column-filter");
		CustomMenuItem item = new CustomMenuItem(content, false);
		menu.getItems().setAll(item);
		menu.getStyleClass().add("column-filter-menu");
		// Jedes Öffnen beginnt mit dem Stand des Filters; so verwirft Schließen ohne „Anwenden“ die Häkchen
		menu.setOnShowing(event -> reload());
	}

	private void reload() {
		Set<Object> deselected = filter.deselected(key);
		search.clear();
		entries.setAll(values.get().stream().map(value -> new Entry(value, !deselected.contains(value.key()))).toList());
	}

	/**
	 * Übernimmt die Häkchen. Ein abgewählter Schlüssel, der in der Werteliste nicht mehr vorkommt, bleibt abgewählt (Spec
	 * Abschnitt 2).
	 */
	private void apply() {
		Set<Object> deselected = new HashSet<>(filter.deselected(key));
		for (Entry entry : entries) {
			if (entry.checked().get()) {
				deselected.remove(entry.value().key());
			} else {
				deselected.add(entry.value().key());
			}
		}
		menu.hide();
		if (filter.setDeselected(key, deselected)) {
			updateSymbol();
			applied.run();
		}
	}

	/** Leert die Filter aller Spalten sofort; die Symbole aller Spalten der Tabelle folgen. */
	private void reset() {
		menu.hide();
		if (filter.clear()) {
			if (column.getTreeTableView() != null) {
				column.getTreeTableView().getColumns().forEach(other -> {
					if (other.getProperties().get(PROPERTY) instanceof ColumnFilterMenu otherMenu) {
						otherMenu.updateSymbol();
					}
				});
			}
			updateSymbol();
			applied.run();
		}
	}

	private void updateSymbol() {
		column.setGraphic(filter.isActive(key) ? funnel : null);
	}
}
