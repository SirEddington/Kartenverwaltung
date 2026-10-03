package de.eltviller_carneval_verein.karten.ui;

import java.util.HashSet;
import java.util.Set;

import de.eltviller_carneval_verein.karten.MainApp;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;

/**
 * Gemeinsame Basis der Verwaltungs-Übersichten (Event-Verwaltung, Hallen-Verwaltung):
 * Baum-Tabelle mit Suchfeld und Rechtsklick-Menü, ohne Inline-Bearbeitung. Die
 * Unterklassen liefern Spalten, Baum, Suchtexte und Menüeinträge; Suche, Neuaufbau
 * (mit erhaltenem Aufklappzustand), Zeilen-Menü und Löschbestätigung liegen hier,
 * damit beide Screens nicht auseinanderlaufen.
 */
public abstract class AbstractOverviewController {

	@FXML protected TextField searchField;
	@FXML protected TreeTableView<Object> treeTableView;

	// Vollständiger Baum; angezeigt wird er bei leerer Suche direkt, sonst eine gefilterte Kopie
	private TreeItem<Object> fullRoot;

	@FXML
	public void initialize() {
		setupColumns();
		treeTableView.setRowFactory(ttv -> createRow());
		reload();
		searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilter());
	}

	/** Konfiguriert die Spalten der Baum-Tabelle. */
	protected abstract void setupColumns();

	/** Baut den vollständigen Baum (Wurzel ist ein unsichtbarer Dummy). */
	protected abstract TreeItem<Object> buildTree();

	/** Text, gegen den die Suche für diesen Baumeintrag prüft (Kleinschreibung wird von der Basis erledigt). */
	protected abstract String searchText(Object data);

	/** Erzeugt das Rechtsklick-Menü; die Sichtbarkeit der Einträge wird beim Anzeigen anhand von {@code row.getItem()} gesetzt. */
	protected abstract ContextMenu createContextMenu(TreeTableRow<Object> row);

	private TreeTableRow<Object> createRow() {
		TreeTableRow<Object> row = new TreeTableRow<>();
		ContextMenu contextMenu = createContextMenu(row);
		// Menü nur an gefüllten Zeilen anzeigen
		row.emptyProperty().addListener((obs, wasEmpty, isEmpty) -> row.setContextMenu(isEmpty ? null : contextMenu));
		return row;
	}

	/** Lädt die Daten neu, behält dabei aufgeklappte Einträge bei und wendet den Suchfilter erneut an. */
	protected void reload() {
		Set<Object> expanded = new HashSet<>();
		collectExpanded(fullRoot, expanded);
		fullRoot = buildTree();
		restoreExpanded(fullRoot, expanded);
		applyFilter();
	}

	private void collectExpanded(TreeItem<Object> item, Set<Object> expanded) {
		if (item == null) {
			return;
		}
		if (item.isExpanded() && item.getValue() != null) {
			expanded.add(item.getValue());
		}
		item.getChildren().forEach(child -> collectExpanded(child, expanded));
	}

	private void restoreExpanded(TreeItem<Object> item, Set<Object> expanded) {
		if (expanded.contains(item.getValue())) {
			item.setExpanded(true);
		}
		item.getChildren().forEach(child -> restoreExpanded(child, expanded));
	}

	private void applyFilter() {
		String query = searchField.getText() == null ? "" : searchField.getText().toLowerCase().trim();
		if (query.isEmpty()) {
			treeTableView.setRoot(fullRoot);
			return;
		}
		TreeItem<Object> filteredRoot = filterNode(fullRoot, query, false);
		treeTableView.setRoot(filteredRoot != null ? filteredRoot : new TreeItem<>("Root"));
	}

	/**
	 * Liefert eine gefilterte Kopie des Knotens oder null, wenn weder er noch ein
	 * Nachfahre zur Suche passt. Passt ein Knoten selbst, bleibt sein ganzer
	 * Unterbaum erhalten. Treffer werden aufgeklappt angezeigt.
	 */
	private TreeItem<Object> filterNode(TreeItem<Object> source, String query, boolean ancestorMatched) {
		boolean isRoot = source.getParent() == null;
		boolean selfMatches = !isRoot && matches(source.getValue(), query);
		boolean keepAllChildren = ancestorMatched || selfMatches;

		TreeItem<Object> copy = new TreeItem<>(source.getValue());
		for (TreeItem<Object> child : source.getChildren()) {
			TreeItem<Object> filteredChild = filterNode(child, query, keepAllChildren);
			if (filteredChild != null) {
				copy.getChildren().add(filteredChild);
			}
		}

		if (isRoot || ancestorMatched || selfMatches || !copy.getChildren().isEmpty()) {
			copy.setExpanded(true);
			return copy;
		}
		return null;
	}

	private boolean matches(Object data, String query) {
		String text = searchText(data);
		return text != null && text.toLowerCase().contains(query);
	}

	/** Sucht vom gewählten Eintrag aus nach oben den ersten Vorfahren (oder sich selbst) vom Typ {@code clazz}. */
	@SuppressWarnings("unchecked")
	protected <T> T findParentInTree(TreeItem<Object> item, Class<T> clazz) {
		TreeItem<Object> current = item;
		while (current != null) {
			if (current.getValue() != null && clazz.isInstance(current.getValue())) {
				return (T) current.getValue();
			}
			current = current.getParent();
		}
		return null;
	}

	protected TreeItem<Object> selectedItem() {
		return treeTableView.getSelectionModel().getSelectedItem();
	}

	/** Zeigt die Löschbestätigung; true, wenn der Benutzer bestätigt hat. */
	protected boolean confirmDelete(String title, String message) {
		Alert confirmation = new Alert(AlertType.CONFIRMATION);
		confirmation.setTitle(title);
		confirmation.setHeaderText(null);
		confirmation.setContentText(message);
		MainApp.applyAppIcon(confirmation);
		return confirmation.showAndWait().filter(button -> button == ButtonType.OK).isPresent();
	}

	@FXML
	protected void handleBackToMenu() {
		MainApp.showManagementMenuView();
	}
}
