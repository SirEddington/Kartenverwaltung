package de.eltviller_carneval_verein.karten.ui.sales;

import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.skin.TreeTableRowSkin;
import javafx.scene.layout.Region;

/**
 * Eine Zeile der Kartentabelle (KARV-193). Eine Gruppenzeile zeigt ihren Kopf in drei Teilen, die Überschrift der
 * Spalte (grau, kleiner), den Wert (fett) und die Zahl („4 Karten“, grau), als eigenen Behälter über der Zeile, nicht
 * in einer Spalte: Er beginnt hinter dem Pfeil (und damit eingerückt nach Ebene) und reicht bis zum rechten Rand der
 * Zeile. Die Zellen darunter sind in Gruppenzeilen leer. Der Kopf nimmt keine Maus an; Klicks treffen weiter die
 * Zellen, wie {@link TicketTreeTable} und JavaFX (Doppelklick klappt) sie erwarten.
 *
 * <p>Die Zeile setzt die Pseudoklassen {@link #GROUP} und {@link TicketTreeTable#GROUP_SELECTED} beim Wechsel ihres
 * Inhalts; Zeilen werden beim Scrollen wiederverwendet. Buchungen ohne Platz sind kursiv.
 */
public final class TicketTreeRow extends TreeTableRow<TicketRow> {

	/** Style-Klasse des Kopfs einer Gruppenzeile (der Behälter der drei Teile). */
	public static final String HEADER_STYLE_CLASS = "group-header";
	/** Style-Klassen der drei Teile des Kopfs. */
	static final String COLUMN_STYLE_CLASS = "group-header-column";
	static final String VALUE_STYLE_CLASS = "group-header-value";
	static final String COUNT_STYLE_CLASS = "group-header-count";
	/** Eine Gruppenzeile, für ihren eigenen Hintergrund und Zellen ohne Spaltenlinien (style.css). */
	static final PseudoClass GROUP = PseudoClass.getPseudoClass("group");
	/** Der feste Abstand zwischen den Teilen des Kopfs. */
	static final double PART_GAP = 10;

	private final TicketTreeTable owner;
	private final GroupHeader header = new GroupHeader();

	public TicketTreeRow(TicketTreeTable owner) {
		this.owner = owner;
	}

	/** Die Zahl im Kopf einer Gruppe: „1 Karte“, sonst „n Karten“. */
	static String countText(int count) {
		return count == 1 ? "1 Karte" : count + " Karten";
	}

	@Override
	protected void updateItem(TicketRow item, boolean empty) {
		super.updateItem(item, empty);
		TicketRow.GroupRow group = !empty && item instanceof TicketRow.GroupRow g ? g : null;
		header.show(group);
		pseudoClassStateChanged(GROUP, group != null);
		pseudoClassStateChanged(TicketTreeTable.GROUP_SELECTED, group != null && owner.isGroupSelected(getTreeItem()));
		// Buchungen ohne Platz kursiv: Sie haben keinen Sitz in der Bestuhlung
		boolean unassigned = !empty && item instanceof TicketRow.BookingRow booking && booking.dto().getBooking().isUnassigned();
		setStyle(unassigned ? "-fx-font-style: italic;" : "");
	}

	/**
	 * Nach dem Layout der Zellen: Der Pfeil kommt in die senkrechte Mitte der Zeile, der Kopf liegt über den Zellen, ab
	 * dem Pfeil bis zum rechten Rand der Zeile. {@code TableRowSkinBase} legt den Pfeil nur in Zeilen bis 24 px Höhe in die
	 * Mitte, in höheren an den oberen Innenrand der Zelle (RT-32700); die Zeilen der Kartentabelle sind 30 px hoch, ihre
	 * Zellen haben oben keinen Innenabstand, der Pfeil saß also oben, der Text des Kopfs in der Mitte. Die Skin setzt ihre
	 * Kinder neu, wenn sich die Spalten ändern; der Kopf kommt dann wieder dazu, wie der Pfeil in {@code TreeTableRowSkin}.
	 */
	@Override
	protected void layoutChildren() {
		super.layoutChildren();
		boolean attached = header.getParent() == this;
		if (!header.isVisible()) {
			if (attached) {
				getChildren().remove(header);
			}
			return;
		}
		if (!attached) {
			getChildren().add(header);
			header.applyCss(); // Schriften der Teile, bevor ihre Breite gemessen wird
		}
		header.toFront();
		double top = snappedTopInset();
		double height = getHeight() - top - snappedBottomInset();
		Node arrow = getDisclosureNode();
		double left = snappedLeftInset();
		if (arrow != null && arrow.getParent() == this) {
			arrow.setLayoutY(snapPositionY(top + (height - arrow.getLayoutBounds().getHeight()) / 2));
			left = arrow.getLayoutX() + arrow.getLayoutBounds().getWidth();
			if (getSkin() instanceof TreeTableRowSkin<?> skin) {
				owner.arrowMeasured(skin.getIndent(), arrow.prefWidth(-1));
			}
		}
		double available = Math.max(0, getWidth() - snappedRightInset() - left);
		header.resizeRelocate(left, top, available, height);
	}

	/**
	 * Der Kopf einer Gruppenzeile: Überschrift, Wert und Zahl nebeneinander, jeder Teil senkrecht mittig. Reicht die
	 * Breite nicht, wird zuerst die Überschrift mit „…“ gekürzt, bis sie ganz entfällt, dann der Wert; die Zahl bleibt
	 * ganz und steht direkt hinter dem (gekürzten) Wert.
	 */
	private static final class GroupHeader extends Region {

		private final Label column = part(COLUMN_STYLE_CLASS);
		private final Label value = part(VALUE_STYLE_CLASS);
		private final Label count = part(COUNT_STYLE_CLASS);

		GroupHeader() {
			getStyleClass().add(HEADER_STYLE_CLASS);
			getChildren().addAll(column, value, count);
			setManaged(false);
			setMouseTransparent(true);
			setVisible(false);
		}

		private static Label part(String styleClass) {
			Label part = new Label();
			part.getStyleClass().add(styleClass);
			part.setEllipsisString("…");
			return part;
		}

		void show(TicketRow.GroupRow group) {
			column.setText(group == null ? null : group.columnsText());
			value.setText(group == null ? null : group.text());
			count.setText(group == null ? null : countText(group.count()));
			setVisible(group != null);
		}

		@Override
		protected void layoutChildren() {
			double x = snappedLeftInset();
			double width = getWidth() - x - snappedRightInset();
			double top = snappedTopInset();
			double height = getHeight() - top - snappedBottomInset();
			double right = x + width;
			// Ganze Pixel: Aufgerundet bekommen Wert und Zahl ihre volle Breite, abgerundet passt der gekürzte Teil davor
			double valueWidth = snapSizeX(value.prefWidth(-1));
			double countWidth = snapSizeX(count.prefWidth(-1));
			// Platz für die Überschrift, wenn Wert und Zahl ganz stehen; schmaler als „…“ entfällt sie
			double columnSpace = Math.floor(width - PART_GAP - valueWidth - PART_GAP - countWidth);
			boolean withColumn = columnSpace >= column.minWidth(-1);
			column.setVisible(withColumn);
			if (withColumn) {
				x = place(column, x, Math.min(snapSizeX(column.prefWidth(-1)), columnSpace), top, height) + PART_GAP;
			}
			x = place(value, x, Math.max(0, Math.min(valueWidth, Math.floor(right - x - PART_GAP - countWidth))), top, height);
			place(count, x + PART_GAP, countWidth, top, height);
		}

		/** Legt einen Teil mit Breite {@code width} senkrecht mittig ab {@code x}; gibt seinen rechten Rand zurück. */
		private double place(Label part, double x, double width, double top, double height) {
			double partHeight = part.prefHeight(-1);
			part.resizeRelocate(x, snapPositionY(top + (height - partHeight) / 2), width, partHeight);
			return x + width;
		}
	}
}
