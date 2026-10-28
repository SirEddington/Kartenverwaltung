package de.eltviller_carneval_verein.karten.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.repository.RepositoryListener;
import de.eltviller_carneval_verein.karten.ui.LiveViews.Registration;
import de.eltviller_carneval_verein.karten.ui.backup.BackupCatalog;
import de.eltviller_carneval_verein.karten.ui.backup.BackupRestoreDialog;
import javafx.fxml.FXML;
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
		// Fremde Änderungen nur, solange die Übersicht zu sehen ist (danach hielten die Repositories sie am Leben)
		LiveViews.whileShown(treeTableView, foreignChangeRegistrations().toArray(new Registration[0]));
	}

	/**
	 * Die Anmeldungen bei den Repositories, deren Änderungen die Übersicht zeigt: Sie gelten, solange die Übersicht zu sehen
	 * ist. Die Listener entstehen mit {@link #foreignChangeReloader()}.
	 */
	protected abstract List<Registration> foreignChangeRegistrations();

	/** Ein Listener, der bei jeder Änderung eines anderen Rechners (geändert, neu, gelöscht) die Übersicht nachführt. */
	protected final <T> RepositoryListener<T> foreignChangeReloader() {
		return RepositoryListener.onForeignChange(this::reloadKeepingSelection);
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

	/**
	 * Wie {@link #reload()}, die Auswahl bleibt: Die neuen Knoten gleichen den bisherigen über {@code equals} (Events,
	 * Vorstellungen und Hallen über die ID, auch wenn ein anderer Rechner das Objekt ersetzt hat). Gibt es den gewählten
	 * Knoten nicht mehr, ist sein nächster vorhandener Vorfahre gewählt.
	 */
	protected void reloadKeepingSelection() {
		List<Object> path = new ArrayList<>();
		for (TreeItem<Object> item = selectedItem(); item != null && item.getParent() != null; item = item.getParent()) {
			path.add(0, item.getValue());
		}
		reload();
		TreeItem<Object> found = treeTableView.getRoot();
		if (found == null) {
			return;
		}
		for (Object value : path) {
			TreeItem<Object> next = found.getChildren().stream().filter(child -> Objects.equals(child.getValue(), value)).findFirst().orElse(null);
			if (next == null) {
				break;
			}
			found.setExpanded(true);
			found = next;
		}
		if (found != treeTableView.getRoot()) {
			treeTableView.getSelectionModel().select(found);
		}
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
		return ConfirmDialog.confirmDelete(title, message);
	}

	/** Der Katalog der Sicherungen dieser Übersicht (Events bzw. Hallen) für den Wiederherstellen-Dialog. */
	protected abstract BackupCatalog backupCatalog();

	/** Öffnet den Dialog zum Wiederherstellen aus den Sicherungen und lädt danach die Übersicht neu. */
	@FXML
	protected void handleRestore() {
		if (BackupRestoreDialog.show(backupCatalog(), treeTableView.getScene().getWindow())) {
			reload();
		}
	}

	@FXML
	protected void handleBackToMenu() {
		MainApp.showManagementMenuView();
	}
}
