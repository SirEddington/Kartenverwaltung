package de.eltviller_carneval_verein.karten.ui.sales;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.skin.TreeTableRowSkin;

/**
 * Eine Zeile der Kartentabelle (KARV-193). Eine Gruppenzeile zeigt ihren Kopf „<Überschrift>: <Text> (<Anzahl>)“ als
 * eigenes Label über der Zeile, nicht in einer Spalte: Es beginnt hinter dem Pfeil (und damit eingerückt nach Ebene)
 * und reicht über so viele Spalten, wie sein Text braucht, höchstens bis zum rechten Rand der Zeile. Die Zellen darunter
 * sind in Gruppenzeilen leer. Das Label nimmt
 * keine Maus an; Klicks treffen weiter die Zellen, wie {@link TicketTreeTable} und JavaFX (Doppelklick klappt) sie
 * erwarten.
 *
 * <p>Die Zeile setzt die Pseudoklasse {@link TicketTreeTable#GROUP_SELECTED} beim Wechsel ihres Inhalts; Zeilen werden
 * beim Scrollen wiederverwendet. Buchungen ohne Platz sind kursiv.
 */
public final class TicketTreeRow extends TreeTableRow<TicketRow> {

	/** Style-Klasse des Kopfs einer Gruppenzeile. */
	public static final String HEADER_STYLE_CLASS = "group-header";

	private final TicketTreeTable owner;
	private final Label header = new Label();

	public TicketTreeRow(TicketTreeTable owner) {
		this.owner = owner;
		header.getStyleClass().add(HEADER_STYLE_CLASS);
		header.setManaged(false);
		header.setMouseTransparent(true);
		header.setVisible(false);
	}

	/** Der Kopf einer Gruppe, etwa „Nachname: Müller (4)“. */
	static String headerText(TicketRow.GroupRow group) {
		return group.columnsText() + ": " + group.text() + " (" + group.count() + ")";
	}

	@Override
	protected void updateItem(TicketRow item, boolean empty) {
		super.updateItem(item, empty);
		TicketRow.GroupRow group = !empty && item instanceof TicketRow.GroupRow g ? g : null;
		header.setText(group == null ? null : headerText(group));
		header.setVisible(group != null);
		pseudoClassStateChanged(TicketTreeTable.GROUP_SELECTED, group != null && owner.isGroupSelected(getTreeItem()));
		// Buchungen ohne Platz kursiv: Sie haben keinen Sitz in der Bestuhlung
		boolean unassigned = !empty && item instanceof TicketRow.BookingRow booking && booking.dto().getBooking().isUnassigned();
		setStyle(unassigned ? "-fx-font-style: italic;" : "");
	}

	/**
	 * Nach dem Layout der Zellen: Der Kopf liegt über ihnen, ab dem Pfeil so breit wie sein Text, höchstens bis zum
	 * rechten Rand der Zeile, über so viele Spalten wie nötig. Mit dem Hintergrund der Zeile (style.css) verdeckt er die
	 * Spaltenlinien nur hinter dem Text; daneben sieht die Zeile aus wie jede andere, gewählt wie eine gewählte. Die Skin
	 * setzt ihre Kinder neu, wenn sich die Spalten ändern; der Kopf kommt dann wieder dazu, wie der Pfeil in
	 * {@code TreeTableRowSkin}.
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
			header.applyCss(); // die fette Schrift, bevor die Breite gemessen wird
		}
		header.toFront();
		Node arrow = getDisclosureNode();
		double left = snappedLeftInset();
		if (arrow != null && arrow.getParent() == this) {
			left = arrow.getLayoutX() + arrow.getLayoutBounds().getWidth();
			if (getSkin() instanceof TreeTableRowSkin<?> skin) {
				owner.arrowMeasured(skin.getIndent(), arrow.prefWidth(-1));
			}
		}
		double available = Math.max(0, getWidth() - snappedRightInset() - left);
		header.resizeRelocate(left, 0, Math.min(available, header.prefWidth(-1)), getHeight());
	}
}
