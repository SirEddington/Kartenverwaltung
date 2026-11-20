package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.ui.CoalescedAction;
import de.eltviller_carneval_verein.karten.ui.ColumnMinWidths;
import de.eltviller_carneval_verein.karten.ui.input.TableGestures;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTablePosition;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/**
 * Die Baumtabelle der Kartentabelle (KARV-193): Sie zeigt den Baum aus {@link TicketGrouping}, hält die Auswahl als
 * Menge von Buchungen ({@link TicketSelection}) und das Auswahlmodell der {@link TreeTableView} nur als Anzeige
 * davon. Klicks und Tasten werden nach der Zeile übertragen, die sie meinen (Spec Abschnitt 6); danach wird die Anzeige
 * angeglichen: Das Auswahlmodell enthält genau die sichtbaren Buchungszeilen der Menge, eine Gruppenzeile ist nie darin
 * und gilt als markiert, wenn alle ihre Buchungen gewählt sind ({@link #GROUP_SELECTED}), eine zugeklappte Gruppe ist
 * nie nur teilweise gewählt.
 *
 * <p>Die Filter für Maus und Tasten hängen am Konstruktor; {@code TableInput.install} muss danach kommen, damit sie vor
 * dessen Filtern laufen. Änderungen am Auswahlmodell, die nicht von hier kommen (Tab und Ziehen aus {@code TableInput}),
 * werden nachträglich übernommen; was JavaFX beim Auf- und Zuklappen, beim Neuaufbau und beim Angleichen am Modell
 * ändert, nicht.
 */
public final class TicketTreeTable {

	/** Pseudoklasse einer Gruppenzeile, deren Buchungen alle gewählt sind (Regel 2). */
	public static final PseudoClass GROUP_SELECTED = PseudoClass.getPseudoClass("group-selected");

	/** Rand rechts vom Pfeil der tiefsten Ebene in seiner Spalte. */
	private static final double TREE_COLUMN_SPACE = 4;

	private final TreeTableView<TicketRow> table;
	private final TreeTableColumn<TicketRow, ?> treeColumn;
	private final TreeItem<TicketRow> root = new TreeItem<>();
	private final TicketSelection selection = new TicketSelection();
	/** Pfade der zugeklappten Gruppen; auch solche, die gerade nicht im Baum stehen. */
	private final Set<List<Object>> collapsed = new HashSet<>();
	private final ReadOnlyObjectWrapper<List<Booking>> selectedBookings = new ReadOnlyObjectWrapper<>(this, "selectedBookings", List.of());

	private List<TicketNode> nodes = List.of();
	private final TicketTreeBuilder tree = new TicketTreeBuilder();
	private List<TicketColumn> grouping = List.of();
	/** Ob die Tabelle gruppiert zeigt: Stand des letzten {@link #setGrouping}. */
	private final ReadOnlyBooleanWrapper grouped = new ReadOnlyBooleanWrapper(this, "grouped", false);
	// Ob die Gruppen wirklich verschachtelt sind; mit weniger als zwei Spalten immer (es gibt nur eine Ebene)
	private boolean groupsNested = true;
	/** Bis zur ersten Messung die Werte von Modena bei 13 px (Einrückung 1em, Pfeil mit Innenabstand). */
	private double indentPerLevel = 13;
	private double arrowWidth = 20;

	private Runnable rebuild = () -> {
	};
	private BooleanSupplier frozen = () -> false;

	/** Größer 0: Das Auswahlmodell ändert sich gerade durch das Angleichen, den Neuaufbau oder JavaFX beim Klappen. */
	private int quiet;
	/** Zwischen {@code sortPolicy} und dem angestoßenen Neuaufbau: Was JavaFX nach der Policy am Modell tut, zählt nicht. */
	private boolean sorting;
	private TreeItem<TicketRow> anchor;
	/** Der laufende Mausdruck bis zum Loslassen. */
	private Press press;
	/** Die Zeile des ersten Klicks eines Doppelklicks ({@link TicketTreeBuilder#keyOf}), {@code null} außerhalb der Zeilen. */
	private Object clickKey;
	/** Eine fremde Änderung am Auswahlmodell wartet auf ihre Übernahme ({@link #adoptModel}). */
	private boolean adoptPending;
	private Press adoptDrag;
	/** Die letzte Taste war Tab: {@code TableInput} wählt die Zeile im neuen Fokus allein. */
	private boolean replaceOnAdopt;
	/** Gleicht bei offenem Verkaufsdialog die Anzeige nach einer abgefangenen Bedienung wieder an, einmal je Takt. */
	private final CoalescedAction resync = new CoalescedAction(Platform::runLater, this::adjust);
	/** Zählt die Schritte des Angleichens; ein nachgeschobener Fokus gilt nur für den letzten. */
	private int focusGeneration;

	/** Ein Mausdruck: was er meint und wie er ausgewertet wird. */
	private static final class Press {
		/** Die gemeinte Zeile; {@code null}: keine Änderung der Menge (Pfeil, Bedienelement, leere Zeile). */
		final TreeItem<TicketRow> item;
		final TreeTableColumn<TicketRow, ?> column;
		final MouseButton button;
		final boolean shift;
		final boolean ctrl;
		final double sceneX;
		final double sceneY;
		final TreeItem<TicketRow> focusBefore;
		/** Die Menge vor dem Druck, für Ziehen mit Strg; sonst leer (nur die linke Taste zieht). */
		final List<Booking> before;
		boolean applied;
		boolean dragging;

		Press(TreeItem<TicketRow> item, TreeTableColumn<TicketRow, ?> column, MouseEvent event, TreeItem<TicketRow> focusBefore,
				Supplier<List<Booking>> before) {
			this.item = item;
			this.column = column;
			this.button = event.getButton();
			this.shift = event.isShiftDown();
			this.ctrl = event.isShortcutDown();
			this.sceneX = event.getSceneX();
			this.sceneY = event.getSceneY();
			this.focusBefore = focusBefore;
			this.before = ctrl && button == MouseButton.PRIMARY ? before.get() : List.of();
		}
	}

	/**
	 * Setzt einmal die Wurzel (unsichtbar), die Spalte für Pfeil und Einrückung, Mehrfachauswahl, die eigene
	 * {@code sortPolicy} und die Filter für Maus und Tasten. Die Wurzel wird danach nie ausgetauscht, denn eine neue
	 * Wurzel leerte die Sortierung.
	 */
	public TicketTreeTable(TreeTableView<TicketRow> table, TreeTableColumn<TicketRow, ?> treeColumn) {
		this.table = table;
		this.treeColumn = treeColumn;
		root.setExpanded(true);
		// vor setRoot: Die Ereignisse zum Klappen erreichen diese Handler vor denen des Auswahlmodells
		root.addEventHandler(TreeItem.<TicketRow>branchCollapsedEvent(), event -> beforeToggle(event.getTreeItem(), false));
		root.addEventHandler(TreeItem.<TicketRow>branchExpandedEvent(), event -> beforeToggle(event.getTreeItem(), true));
		table.setRoot(root);
		table.setShowRoot(false);
		table.setTreeColumn(treeColumn);
		table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		table.getSelectionModel().getSelectedIndices().addListener((ListChangeListener<Integer>) change -> modelChanged());
		// Die Policy läuft in startAtomic(): Sie ordnet nichts selbst, sondern stößt den Neuaufbau an
		table.setSortPolicy(view -> {
			sorting = true;
			Platform.runLater(() -> {
				sorting = false;
				rebuild.run();
			});
			return true;
		});
		// je Ereignistyp, nicht für ANY: JavaFX ruft die Filter des genauen Typs vor denen des Obertyps, also sonst nach
		// denen von TableInput
		for (EventType<MouseEvent> type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_RELEASED,
				MouseEvent.MOUSE_CLICKED)) {
			table.addEventFilter(type, this::mouseFilter);
		}
		table.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> applyPress());
		table.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> finishPress());
		for (EventType<KeyEvent> type : List.of(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED, KeyEvent.KEY_TYPED)) {
			table.addEventFilter(type, this::keyFilter);
		}
	}

	/** Was die {@code sortPolicy} anstößt: Der Controller rechnet die Knoten und ruft {@link #show}. */
	public void setRebuild(Runnable rebuild) {
		this.rebuild = rebuild;
	}

	/** Solange {@code frozen} gilt (Verkaufsdialog offen), ändert keine Bedienung die Auswahl, auch kein Auf- oder Zuklappen. */
	public void setFrozen(BooleanSupplier frozen) {
		this.frozen = frozen;
	}

	// ---- Neuaufbau ----

	/**
	 * Zeigt den Baum. Bei gleicher Struktur (Pfade, Buchungen je Gruppe, Reihenfolge, Kopftexte) wird nur neu gezeichnet;
	 * sonst werden die Kinder der Wurzel ersetzt, die Gruppen nach den gemerkten zugeklappten Pfaden geklappt und die
	 * Anzeige angeglichen. Die Menge bleibt bis auf Buchungen, die nicht mehr im Baum stehen; Fokus und Anker folgen ihrer
	 * Buchung bzw. dem Pfad ihrer Gruppe.
	 */
	public void show(List<TicketNode> newNodes) {
		flushAdopt();
		if (TicketTreeBuilder.sameStructure(nodes, newNodes)) {
			table.refresh();
			markGroups();
			return;
		}
		Object focusKey = tree.keyOf(focusedItem());
		TreeTableColumn<TicketRow, ?> focusColumn = focusedColumn();
		Object anchorKey = tree.keyOf(anchor);
		quiet++;
		try {
			nodes = List.copyOf(newNodes);
			// Ersetzt JavaFX die Kinder, während viele Zeilen gewählt sind, räumt es die Auswahl Zeile für Zeile um (gemessen
			// mit 3000 gewählten Buchungen rund 75 bis 120 ms); das Angleichen wählt danach ohnehin neu
			if (!table.getSelectionModel().isEmpty()) {
				table.getSelectionModel().clearSelection();
			}
			root.getChildren().setAll(tree.build(nodes, collapsed, this::toggled));
			selection.rebuilt(nodes);
		} finally {
			quiet--;
		}
		anchor = tree.itemOf(anchorKey);
		adjust(tree.itemOf(focusKey), focusColumn);
		publish();
	}

	// ---- Sortieren ----

	/**
	 * Ordnet Buchungen nach {@code getSortOrder()}: Zellwert über die Wert-Fabrik der Spalte, Comparator und Richtung der
	 * Spalte; Gleichstand bleibt dem Aufrufer (Grundordnung). {@code null} ohne Sortierung.
	 */
	public Comparator<BookingDTO> sortComparator() {
		return TicketTreeBuilder.sortComparator(List.copyOf(table.getSortOrder()));
	}

	// ---- Auswahl nach außen ----

	/** Die gewählten Buchungen in Reihenfolge der Tabelle, auch die in zugeklappten Gruppen. */
	public List<Booking> selectedBookings() {
		return selection.selected(nodes);
	}

	/** Die Menge, gemeldet nur nach einem abgeschlossenen Schritt. */
	public ReadOnlyObjectProperty<List<Booking>> selectedBookingsProperty() {
		return selectedBookings.getReadOnlyProperty();
	}

	/** Menge = die Buchungen aus {@code bookings}, die in der Tabelle stehen; teilweise gewählte Gruppen gehen auf. */
	public void restoreSelection(Collection<Booking> bookings) {
		flushAdopt();
		selection.set(bookings, nodes);
		adjust();
		publish();
	}

	/** Nimmt die Buchungen aus der Menge; eine bisher ganz gewählte, zugeklappte Gruppe geht dabei auf. */
	public void deselect(Collection<Booking> bookings) {
		flushAdopt();
		selection.remove(bookings);
		adjust();
		publish();
	}

	/** Menge = diese Buchung; ihre Gruppen gehen auf, die Zeile bekommt Fokus und Anker und wird sichtbar gescrollt. */
	public void selectAndReveal(Booking booking) {
		flushAdopt();
		selection.set(List.of(booking), nodes);
		TreeItem<TicketRow> item = tree.item(booking);
		if (item != null) {
			quiet++;
			try {
				for (TreeItem<TicketRow> parent = item.getParent(); parent != null && parent != root; parent = parent.getParent()) {
					parent.setExpanded(true);
				}
			} finally {
				quiet--;
			}
			anchor = item;
		}
		adjust(item, focusedColumn());
		if (item != null) {
			scrollTo(table.getRow(item));
		}
		publish();
	}

	public void clearSelection() {
		flushAdopt();
		selection.clear();
		adjust();
		publish();
	}

	// ---- Gruppierung und Spalte für Pfeil und Einrückung ----

	/**
	 * Die Gruppierung, nach der der nächste Baum für {@link #show} gebaut ist; vor jedem Neuaufbau aufzurufen. Eine andere
	 * Gruppierung als bisher vergisst die zugeklappten Gruppen ({@link #clearCollapsed}). Die Spalte für Pfeil und
	 * Einrückung ist nur mit Gruppierung zu sehen und mindestens so breit wie Pfeil und Einrückung der tiefsten Ebene:
	 * JavaFX blendet den Pfeil sonst aus. Ausgeblendet rückt sie keine Datenspalte ein, denn sie bleibt die
	 * {@code treeColumn} der Tabelle (Spec Abschnitt 4).
	 */
	public void setGrouping(List<TicketColumn> columns, boolean nested) {
		List<TicketColumn> next = List.copyOf(columns);
		// Mit weniger als zwei Spalten gibt es nichts zu verschachteln: Dann ändert das Umschalten die Gruppen nicht
		boolean nextNested = nested || next.size() < 2;
		if (!next.equals(grouping) || nextNested != groupsNested) {
			grouping = next;
			groupsNested = nextNested;
			clearCollapsed();
		}
		grouped.set(!grouping.isEmpty());
		treeColumn.setVisible(grouped.get());
		updateTreeColumnFloor();
	}

	/** Ob die Tabelle gruppiert zeigt, nach der Gruppierung des letzten {@link #setGrouping}, nicht nach der schon gewählten. */
	public ReadOnlyBooleanProperty groupedProperty() {
		return grouped.getReadOnlyProperty();
	}

	/**
	 * Was eine Gruppenzeile beim Layout misst ({@link TicketTreeRow}): Einrückung je Ebene und Breite des Pfeils, beide
	 * aus dem Stylesheet. Ändern sie sich, passt die Mindestbreite der Spalte für den Pfeil sich an.
	 */
	void arrowMeasured(double indent, double arrowWidth) {
		if (indent != indentPerLevel || arrowWidth != this.arrowWidth) {
			indentPerLevel = indent;
			this.arrowWidth = arrowWidth;
			updateTreeColumnFloor();
		}
	}

	private void updateTreeColumnFloor() {
		if (!grouping.isEmpty()) {
			int levels = groupsNested ? grouping.size() : 1;
			ColumnMinWidths.setFloor(treeColumn, indentPerLevel * (levels - 1) + arrowWidth + TREE_COLUMN_SPACE);
		}
	}

	/** Vergisst die zugeklappten Gruppen und klappt alle auf (andere oder keine Gruppierung). */
	void clearCollapsed() {
		collapsed.clear();
		quiet++;
		try {
			tree.groupItems().forEach(item -> item.setExpanded(true));
		} finally {
			quiet--;
		}
		adjust();
	}

	/** Alle Buchungen des Baums in Reihenfolge der Tabelle, auch die in zugeklappten Gruppen. */
	public List<Booking> allBookings() {
		return TicketSelection.bookings(nodes);
	}

	/** Die Buchungen einer Zeile: die eine Buchung bzw. alle Buchungen unter der Gruppe, auch in zugeklappten Gruppen. */
	public List<Booking> bookingsUnder(TreeItem<TicketRow> item) {
		TicketNode node = tree.node(item);
		return node == null ? List.of() : TicketSelection.bookings(List.of(node));
	}

	/** Regel 2: ob die Zeile eine Gruppe ist, deren Buchungen alle gewählt sind. */
	public boolean isGroupSelected(TreeItem<?> item) {
		TicketNode node = tree.node(item);
		return node != null && node.isGroup() && selection.fullySelected(node);
	}

	private void publish() {
		List<Booking> now = selectedBookings();
		List<Booking> shown = selectedBookings.get();
		boolean same = now.size() == shown.size();
		for (int i = 0; same && i < now.size(); i++) {
			same = now.get(i) == shown.get(i);
		}
		if (!same) {
			selectedBookings.set(now);
		}
	}

	// ---- Angleichen ----

	/** Gleicht die Anzeige an die Menge an; der Fokus bleibt auf seiner Zeile. */
	private void adjust() {
		adjust(focusedItem(), focusedColumn());
	}

	/**
	 * Regel 3 (teilweise gewählte, zugeklappte Gruppen aufklappen), Auswahlmodell = sichtbare Buchungszeilen der Menge,
	 * Markierung der Gruppenzeilen nach Regel 2, Fokus auf {@code focus}; steckt die Zeile in einer zugeklappten Gruppe, auf
	 * der nächsten sichtbaren Gruppe darüber, und auf keiner Zeile, wenn sie nicht mehr im Baum steht ({@link #applyFocus}).
	 */
	private void adjust(TreeItem<TicketRow> focus, TreeTableColumn<TicketRow, ?> column) {
		quiet++;
		try {
			for (List<Object> path : selection.pathsToExpand(nodes, collapsed)) {
				TreeItem<TicketRow> item = tree.item(path);
				if (item != null) {
					item.setExpanded(true);
				}
			}
			List<Integer> wanted = new ArrayList<>();
			forEachShown(0, Integer.MAX_VALUE, (node, row) -> {
				if (node.isBooking() && selection.fullySelected(node)) {
					wanted.add(row);
				}
			});
			List<Integer> current = new ArrayList<>(table.getSelectionModel().getSelectedIndices());
			Collections.sort(current);
			if (!wanted.equals(current)) {
				table.getSelectionModel().clearSelection();
				if (!wanted.isEmpty()) {
					int[] rest = wanted.subList(1, wanted.size()).stream().mapToInt(Integer::intValue).toArray();
					table.getSelectionModel().selectIndices(wanted.get(0), rest);
				}
			}
			applyFocus(focus, column);
		} finally {
			quiet--;
		}
		markGroups();
		applyFocusLater(focus, column);
	}

	/**
	 * Nach dem Ersetzen von Kindern und dem Klappen verschiebt JavaFX den Fokus erst später (Platform.runLater im
	 * TreeTableViewFocusModel); dieser Aufruf kommt danach und setzt ihn wieder auf die gemeinte Zeile, nur für das letzte
	 * Angleichen.
	 */
	private void applyFocusLater(TreeItem<TicketRow> focus, TreeTableColumn<TicketRow, ?> column) {
		int generation = ++focusGeneration;
		Platform.runLater(() -> {
			if (generation == focusGeneration) {
				applyFocus(focus, column);
			}
		});
	}

	private void applyFocus(TreeItem<TicketRow> focus, TreeTableColumn<TicketRow, ?> column) {
		int row = focus == null ? -1 : table.getRow(focus);
		// steckt die Zeile in einer zugeklappten Gruppe, steht der Fokus auf der nächsten sichtbaren Gruppe darüber
		for (TreeItem<TicketRow> parent = focus == null ? null : focus.getParent(); row < 0 && parent != null && parent != root; parent = parent
				.getParent()) {
			row = table.getRow(parent);
		}
		TreeTablePosition<TicketRow, ?> focused = table.getFocusModel().getFocusedCell();
		if (focused == null || focused.getRow() != row || focused.getTableColumn() != column) {
			if (row < 0) {
				table.getFocusModel().focus(-1);
			} else {
				table.getFocusModel().focus(row, column);
			}
		}
	}

	/** Setzt die Pseudoklasse der sichtbaren Zeilen; die Zeilenfabrik setzt sie beim Wechsel des Inhalts selbst. */
	private void markGroups() {
		for (Node node : table.lookupAll(".tree-table-row-cell")) {
			if (node instanceof TreeTableRow<?> row) {
				row.pseudoClassStateChanged(GROUP_SELECTED, isGroupSelected(row.getTreeItem()));
			}
		}
	}

	// ---- Auf- und Zuklappen ----

	/** Läuft, bevor JavaFX beim Klappen das Auswahlmodell ändert; {@link #toggled} beendet den Schritt. */
	private void beforeToggle(TreeItem<TicketRow> item, boolean expanding) {
		TicketNode node = tree.node(item);
		if (node == null || !node.isGroup()) {
			return;
		}
		boolean byUser = quiet == 0;
		quiet++;
		if (!expanding && byUser) {
			selection.collapsing(node); // Regel zum Zuklappen, für jeden Auslöser
		}
	}

	private void toggled(TreeItem<TicketRow> item, boolean expanded) {
		TicketNode node = tree.node(item);
		if (node == null || !(node.row() instanceof TicketRow.GroupRow group)) {
			return;
		}
		if (expanded) {
			collapsed.remove(group.path());
		} else {
			collapsed.add(group.path());
		}
		quiet--;
		if (quiet == 0) {
			adjust();
			publish();
		}
	}

	private void expandWithSubgroups(TreeItem<TicketRow> item) {
		if (tree.node(item) instanceof TicketNode node && node.isGroup()) {
			item.setExpanded(true);
			item.getChildren().forEach(this::expandWithSubgroups);
		}
	}

	/**
	 * „Alle aufklappen“ (Spec 14.1): jede Gruppe auf jeder Ebene auf, die zugeklappten Pfade vergessen; Auswahl und Fokus
	 * bleiben. Ohne Wirkung bei offenem Verkaufsdialog und ohne Gruppierung. Eine laufende Bearbeitung beendet der Aufrufer.
	 */
	public void expandAll() {
		if (frozen.getAsBoolean() || !grouped.get()) {
			return;
		}
		flushAdopt();
		clearCollapsed();
		publish();
	}

	/**
	 * „Alle zuklappen“ (Spec 14.1): jede Gruppe auf jeder Ebene zu, auch die inneren. Die Regel zum Zuklappen gilt einmal
	 * für das Ganze ({@link TicketSelection#collapsingAll}), nicht je Gruppe; danach eine Meldung. Fokus und Anker gehen auf
	 * die oberste Gruppe der bisher fokussierten Zeile. Ohne Wirkung bei offenem Verkaufsdialog und ohne Gruppierung. Eine
	 * laufende Bearbeitung beendet der Aufrufer.
	 */
	public void collapseAll() {
		if (frozen.getAsBoolean() || !grouped.get()) {
			return;
		}
		flushAdopt();
		TreeItem<TicketRow> top = topLevel(focusedItem());
		TreeTableColumn<TicketRow, ?> column = focusedColumn();
		selection.collapsingAll(nodes);
		quiet++; // beforeToggle und toggled wenden die Regel so nicht je Gruppe an und gleichen nicht je Gruppe an
		try {
			// wie beim Neuaufbau: sonst räumt JavaFX bei jeder Gruppe die Auswahl Zeile für Zeile um
			if (!table.getSelectionModel().isEmpty()) {
				table.getSelectionModel().clearSelection();
			}
			for (TreeItem<TicketRow> item : tree.groupItems()) {
				item.setExpanded(false); // toggled merkt den Pfad in collapsed
			}
		} finally {
			quiet--;
		}
		anchor = top;
		adjust(top, column);
		publish();
	}

	/** Die oberste Gruppe über der Zeile (bzw. die Zeile selbst, steht sie oben); {@code null} ohne Zeile. */
	private TreeItem<TicketRow> topLevel(TreeItem<TicketRow> item) {
		TreeItem<TicketRow> top = item;
		while (top != null && top.getParent() != null && top.getParent() != root) {
			top = top.getParent();
		}
		return top;
	}

	// ---- Fremde Änderungen am Auswahlmodell ----

	private void modelChanged() {
		if (quiet > 0 || sorting) {
			return;
		}
		if (frozen.getAsBoolean()) {
			resync.request();
			return;
		}
		if (press != null && !press.dragging) {
			return; // der Druck wird beim Loslassen angeglichen
		}
		if (!adoptPending) {
			adoptPending = true;
			adoptDrag = press;
			// nicht hier: Das Auswahlmodell darf während seiner eigenen Meldung nicht geändert werden
			Platform.runLater(this::flushAdopt);
		}
	}

	private void flushAdopt() {
		if (adoptPending) {
			adoptModel();
		}
	}

	/**
	 * Übernimmt eine Änderung am Auswahlmodell, die nicht von hier kam: Ziehen als Bereich (mit Strg zur Menge vor dem
	 * Druck), Tab als Wahl der einen Buchung, sonst nur, was an sichtbaren Buchungen hinzu- oder weggekommen ist.
	 */
	private void adoptModel() {
		adoptPending = false;
		Press drag = adoptDrag;
		adoptDrag = null;
		List<TicketNode> rows = new ArrayList<>();
		Set<Booking> inModel = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Integer> indices = new ArrayList<>(table.getSelectionModel().getSelectedIndices());
		Collections.sort(indices);
		for (int index : indices) {
			TicketNode node = tree.node(table.getTreeItem(index));
			if (node != null) {
				rows.add(node);
				if (node.row() instanceof TicketRow.BookingRow booking) {
					inModel.add(booking.dto().getBooking());
				}
			}
		}
		if (drag != null) {
			if (drag.ctrl) {
				selection.set(drag.before, nodes);
			}
			selection.range(rows, drag.ctrl);
		} else if (replaceOnAdopt) {
			selection.range(rows, false);
			anchor = focusedItem();
		} else {
			List<Booking> removed = new ArrayList<>();
			forEachShown(0, Integer.MAX_VALUE, (node, row) -> {
				if (node.row() instanceof TicketRow.BookingRow booking) {
					Booking shown = booking.dto().getBooking();
					if (inModel.contains(shown)) {
						selection.add(node);
					} else if (selection.fullySelected(node)) {
						removed.add(shown);
					}
				}
			});
			selection.remove(removed);
		}
		replaceOnAdopt = false;
		adjust();
		publish();
	}

	// ---- Maus ----

	private void mouseFilter(MouseEvent event) {
		EventType<? extends MouseEvent> type = event.getEventType();
		TreeTableRow<?> row = (TreeTableRow<?>) TableGestures.rowOf(event.getTarget(), TreeTableRow.class::isInstance);
		if (frozen.getAsBoolean()) {
			if (row != null) {
				event.consume();
				resync.request(); // Filter auf derselben Tabelle (Ziehen in TableInput) laufen trotzdem
			}
			return;
		}
		if (type == MouseEvent.MOUSE_PRESSED || type == MouseEvent.MOUSE_RELEASED) {
			guardMultiClick(event, row);
		}
		if (type == MouseEvent.MOUSE_PRESSED) {
			onPress(event, row);
		} else if (type == MouseEvent.MOUSE_DRAGGED) {
			if (press != null && !press.dragging && press.button == MouseButton.PRIMARY
					&& Math.hypot(event.getSceneX() - press.sceneX, event.getSceneY() - press.sceneY) >= TableGestures.DRAG_THRESHOLD) {
				press.dragging = true;
			}
		} else if (type == MouseEvent.MOUSE_RELEASED && press != null) {
			Press done = press;
			if (done.dragging) {
				// TableInput verbraucht das Loslassen nach dem Ziehen; der Bereich steht schon im Auswahlmodell
				flushAdopt();
				press = null;
			} else {
				// falls ein Bedienelement das Loslassen verbraucht
				Platform.runLater(() -> {
					if (press == done) {
						finishPress();
					}
				});
			}
		}
	}

	/**
	 * Ein Doppelklick bearbeitet nur die Zeile seines ersten Klicks. Ein Neuaufbau zwischen den Klicks (etwa nach dem Commit,
	 * den der erste Druck auslöst) kann eine andere Buchung unter den Zeiger schieben; JavaFX begänne beim zweiten Druck
	 * oder, ist die Zeile dort schon gewählt, beim Loslassen ihr Bearbeiten. Trifft ein Druck oder Loslassen mit Klickzahl
	 * ab 2 eine andere Zeile, ist die Tabelle für die Dauer dieses Ereignisses nicht bearbeitbar
	 * ({@link TableGestures#suspendEditing}). Nicht, solange eine Zelle bearbeitet wird: Deren Ende
	 * ({@code table.edit(-1, null)}) bliebe sonst ohne Wirkung.
	 */
	private void guardMultiClick(MouseEvent event, TreeTableRow<?> row) {
		if (event.getButton() != MouseButton.PRIMARY) {
			return;
		}
		Object key = row == null || row.isEmpty() ? null : tree.keyOf(row.getTreeItem());
		if (event.getClickCount() < 2) {
			if (event.getEventType() == MouseEvent.MOUSE_PRESSED) {
				clickKey = key;
			}
		} else if (!Objects.equals(key, clickKey) && table.isEditable() && table.getEditingCell() == null) {
			TableGestures.suspendEditing(table::setEditable);
		}
	}

	private void onPress(MouseEvent event, TreeTableRow<?> row) {
		flushAdopt();
		replaceOnAdopt = false;
		press = null;
		if (row == null) {
			return; // Kopf, Scroll-Balken
		}
		TreeItem<TicketRow> item = row.isEmpty() ? null : tree.typed(row.getTreeItem());
		// Eine Checkbox, Auswahlliste oder ein Textfeld behält den Druck für sich; die Auswahl ändert sich nicht
		if (item != null && (TableGestures.insideControl(event.getTarget(), node -> node == row) || onDisclosure(event, row))) {
			item = null;
		}
		press = new Press(item, columnOf(event.getTarget()), event, focusedItem(), this::selectedBookings);
	}

	/** Nach der Auswertung durch JavaFX: Die Menge folgt der gemeinten Zeile (Spec Abschnitt 6). */
	private void applyPress() {
		if (press == null || press.applied) {
			return;
		}
		press.applied = true;
		TreeItem<TicketRow> item = press.item;
		TicketNode node = item == null ? null : tree.node(item);
		if (node != null) {
			if (press.button == MouseButton.PRIMARY) {
				if (press.shift) {
					selection.range(rowsBetween(anchorRow(press.focusBefore), table.getRow(item)), press.ctrl);
				} else if (press.ctrl) {
					selection.toggle(node);
					anchor = item;
				} else {
					selection.select(node);
					anchor = item;
				}
			} else if (press.button == MouseButton.SECONDARY && !selection.fullySelected(node)) {
				selection.select(node);
				anchor = item;
			}
			adjust(item, press.column);
		} else {
			adjust();
		}
		publish();
	}

	/** Nach dem Loslassen: JavaFX wählt bei einer schon gewählten Zeile erst jetzt; das wird überschrieben. */
	private void finishPress() {
		Press done = press;
		if (done == null || done.dragging) {
			return;
		}
		press = null;
		if (done.applied && done.item != null && table.getRow(done.item) >= 0) {
			adjust(done.item, done.column);
		} else {
			adjust();
		}
		publish();
	}

	@SuppressWarnings("unchecked")
	private static TreeTableColumn<TicketRow, ?> columnOf(Object target) {
		return TableGestures.ancestor(target, TreeTableCell.class::isInstance) instanceof TreeTableCell<?, ?> cell
				? (TreeTableColumn<TicketRow, ?>) cell.getTableColumn()
				: null;
	}

	/** Wie JavaFX ({@code TreeTableCellBehavior.handleDisclosureNode}): ein Druck in der Baumspalte links vom Pfeilende. */
	private boolean onDisclosure(MouseEvent event, TreeTableRow<?> row) {
		Node arrow = row.getDisclosureNode();
		if (arrow == null || !arrow.isVisible()) {
			return false;
		}
		if (TableGestures.ancestor(event.getTarget(), node -> node == arrow || node == row) == arrow) {
			return true;
		}
		if (columnOf(event.getTarget()) != treeColumn) {
			return false;
		}
		Bounds bounds = arrow.localToScene(arrow.getBoundsInLocal());
		return event.getSceneX() < bounds.getMaxX();
	}

	// ---- Tasten ----

	/** Nur {@code KEY_PRESSED} wird verbraucht, nie {@code KEY_TYPED}: Ein getipptes „-“ beginnt weiter das Bearbeiten. */
	private void keyFilter(KeyEvent event) {
		if (frozen.getAsBoolean()) {
			event.consume();
			resync.request();
			return;
		}
		if (event.getEventType() != KeyEvent.KEY_PRESSED) {
			return;
		}
		flushAdopt();
		replaceOnAdopt = event.getCode() == KeyCode.TAB;
		if (table.getEditingCell() != null || event.isAltDown() || event.isMetaDown()) {
			return;
		}
		boolean shift = event.isShiftDown();
		boolean ctrl = event.isShortcutDown();
		int all = ctrl ? expandAllKey(event) : 0;
		if (all != 0) {
			if (!grouped.get()) {
				return; // ohne Gruppierung tun Strg+Plus und Strg+Minus nichts und bleiben unverbraucht
			}
			if (all > 0) {
				expandAll();
			} else {
				collapseAll();
			}
			event.consume();
			return;
		}
		TreeItem<TicketRow> item = focusedItem();
		int focus = item == null ? table.getFocusModel().getFocusedIndex() : table.getRow(item);
		TicketNode node = tree.node(item);
		boolean group = node != null && node.isGroup();
		switch (event.getCode()) {
		case UP, KP_UP -> navigate(focus - 1, shift, ctrl);
		case DOWN, KP_DOWN -> navigate(focus + 1, shift, ctrl);
		case HOME -> navigate(0, shift, ctrl);
		case END -> navigate(table.getExpandedItemCount() - 1, shift, ctrl);
		case PAGE_UP -> navigate(pageTarget(focus, false), shift, ctrl);
		case PAGE_DOWN -> navigate(pageTarget(focus, true), shift, ctrl);
		case LEFT, KP_LEFT -> leftRight(item, node, false, shift || ctrl);
		case RIGHT, KP_RIGHT -> leftRight(item, node, true, shift || ctrl);
		case ADD, SUBTRACT, MULTIPLY -> {
			if (shift || ctrl) {
				return;
			}
			if (group) {
				expandKey(event.getCode(), item);
			}
		}
		case SPACE -> {
			if (node == null) {
				return; // ohne Zeile im Fokus tut die Leertaste nichts
			}
			space(item, node, shift, ctrl);
		}
		case ENTER, F2 -> {
			if (!group) {
				return; // Bearbeiten über TableInput bzw. JavaFX
			}
		}
		case A -> {
			if (!ctrl || shift) {
				return;
			}
			selection.selectAll(nodes);
			adjust();
			publish();
		}
		default -> {
			return;
		}
		}
		event.consume();
	}

	/** +, − und * auf einem Gruppenkopf: aufklappen, zuklappen, mit allen Untergruppen aufklappen. */
	private void expandKey(KeyCode code, TreeItem<TicketRow> group) {
		switch (code) {
		case ADD -> group.setExpanded(true);
		case SUBTRACT -> group.setExpanded(false);
		default -> expandWithSubgroups(group);
		}
	}

	/**
	 * Strg+Plus bzw. Strg+Minus, mit der Taste des Hauptfelds oder des Ziffernblocks: {@code 1} für „Alle aufklappen“,
	 * {@code -1} für „Alle zuklappen“, sonst {@code 0}. Liefert eine Tastatur andere Codes, entscheidet der getippte Text.
	 */
	private static int expandAllKey(KeyEvent event) {
		return switch (event.getCode()) {
		case PLUS, ADD -> 1;
		case MINUS, SUBTRACT -> -1;
		default -> switch (event.getText()) {
		case "+" -> 1;
		case "-" -> -1;
		default -> 0;
		};
		};
	}

	/** Pfeil auf/ab, Pos1, Ende, Bild: ohne Umschalt Menge = Zeile im Fokus, mit Strg nur der Fokus, mit Umschalt Bereich. */
	private void navigate(int target, boolean shift, boolean ctrl) {
		int count = table.getExpandedItemCount();
		if (count == 0) {
			return;
		}
		int row = Math.max(0, Math.min(count - 1, target));
		TreeItem<TicketRow> item = table.getTreeItem(row);
		TicketNode node = tree.node(item);
		if (shift) {
			selection.range(rowsBetween(anchorRow(focusedItem()), row), ctrl);
		} else if (!ctrl && node != null) {
			selection.select(node);
			anchor = item;
		}
		scrollTo(row);
		adjust(item, focusedColumn());
		publish();
	}

	/** In einer Buchungszeile wie in der TableView die Spalte wechseln; auf einem Gruppenkopf auf- bzw. zuklappen. */
	private void leftRight(TreeItem<TicketRow> item, TicketNode node, boolean right, boolean modified) {
		if (node == null) {
			return;
		}
		if (node.isGroup()) {
			if (modified) {
				return;
			}
			if (right) {
				item.setExpanded(true);
			} else if (item.isExpanded()) {
				item.setExpanded(false);
			} else if (item.getParent() != null && item.getParent() != root) {
				adjust(item.getParent(), focusedColumn());
			}
			return;
		}
		List<TreeTableColumn<TicketRow, ?>> columns = new ArrayList<>(table.getVisibleLeafColumns());
		columns.remove(treeColumn);
		int next = columns.indexOf(focusedColumn()) + (right ? 1 : -1);
		if (next < 0 || next >= columns.size()) {
			return;
		}
		if (!modified) {
			selection.select(node);
			anchor = item;
		}
		adjust(item, columns.get(next));
		publish();
	}

	/** Leertaste: hinein (in einer Buchungszeile danach Bearbeiten wie bei JavaFX), Strg: umschalten, Umschalt: Bereich. */
	private void space(TreeItem<TicketRow> item, TicketNode node, boolean shift, boolean ctrl) {
		if (shift) {
			selection.range(rowsBetween(anchorRow(item), table.getRow(item)), ctrl);
		} else {
			if (ctrl) {
				selection.toggle(node);
			} else {
				selection.add(node);
			}
			anchor = item;
		}
		TreeTableColumn<TicketRow, ?> column = focusedColumn();
		adjust(item, column);
		publish();
		if (!shift && !ctrl && node.isBooking() && table.isEditable() && column != null && column.isEditable()) {
			table.edit(table.getRow(item), column);
		}
	}

	private int pageTarget(int focus, boolean down) {
		VirtualFlow<?> flow = TableGestures.flowOf(table);
		if (flow != null && flow.getFirstVisibleCell() != null && flow.getLastVisibleCell() != null) {
			int first = flow.getFirstVisibleCell().getIndex();
			int last = flow.getLastVisibleCell().getIndex();
			int page = Math.max(1, last - first);
			if (down) {
				return focus < last ? last : focus + page;
			}
			return focus > first ? first : focus - page;
		}
		return focus + (down ? 10 : -10);
	}

	private void scrollTo(int row) {
		if (row >= 0) {
			TableGestures.scrollIntoView(table, row, table::scrollTo);
		}
	}

	// ---- Hilfen ----

	/**
	 * Die Zeile im Fokus als gespeichertes Element, nicht über {@code getTreeItem(index)}: Nach dem Klappen verschiebt
	 * JavaFX den Index erst später, bis dahin nennt er eine andere Zeile.
	 */
	private TreeItem<TicketRow> focusedItem() {
		return tree.typed(table.getFocusModel().getFocusedItem());
	}

	private TreeTableColumn<TicketRow, ?> focusedColumn() {
		TreeTablePosition<TicketRow, ?> focused = table.getFocusModel().getFocusedCell();
		return focused == null ? null : focused.getTableColumn();
	}

	/** Die Zeile des Ankers; ist er nicht zu sehen, die Zeile {@code fallback} (der Fokus vor der Bedienung). */
	private int anchorRow(TreeItem<TicketRow> fallback) {
		int row = anchor == null ? -1 : table.getRow(anchor);
		return row >= 0 ? row : fallback == null ? -1 : table.getRow(fallback);
	}

	/** Die sichtbaren Zeilen zwischen {@code from} und {@code to} einschließlich; ohne Anker nur {@code to}. */
	private List<TicketNode> rowsBetween(int from, int to) {
		int start = from < 0 ? to : Math.min(from, to);
		int end = from < 0 ? to : Math.max(from, to);
		List<TicketNode> rows = new ArrayList<>();
		forEachShown(start, end, (node, row) -> rows.add(node));
		return rows;
	}

	/** Für jede sichtbare Zeile von {@code from} bis {@code to} einschließlich, die zu diesem Baum gehört: Knoten und Zeile. */
	private void forEachShown(int from, int to, ObjIntConsumer<TicketNode> action) {
		int end = Math.min(to, table.getExpandedItemCount() - 1);
		for (int row = Math.max(0, from); row <= end; row++) {
			TicketNode node = tree.node(table.getTreeItem(row));
			if (node != null) {
				action.accept(node, row);
			}
		}
	}
}
