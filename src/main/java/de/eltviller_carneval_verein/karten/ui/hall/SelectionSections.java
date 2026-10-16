package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;

/**
 * Die aufklappbaren Abschnitte "Reihe", "Tisch" und "Sitz" im Reiter "Bestuhlung": Zur Markierung zeigt das Panel die
 * ganze Kette untereinander (Reihe; Reihe und Tisch; Reihe, Tisch und Sitz), jeder Abschnitt mit dem Kurznamen als
 * Titel und den Feldern des Eintrags darunter.
 *
 * <p>
 * Aufgeklappt ist zunächst nur der Abschnitt des markierten Eintrags, die der übergeordneten sind zugeklappt. Klappt
 * der Nutzer den Abschnitt einer Ebene selbst auf oder zu, gilt seine Wahl für diese Ebene, bis der Editor geschlossen
 * wird; ein Wechsel der Markierung setzt sie nicht zurück.
 *
 * <p>
 * Die Entscheidung ({@link #sections}) kennt kein JavaFX und ist einzeln testbar; {@link #bind} und {@link #show}
 * übertragen sie auf die {@link TitledPane}s.
 */
final class SelectionSections {

	/** Ebene eines Abschnitts, von oben nach unten. */
	enum Level {
		ROW, TABLE, SEAT
	}

	/** Ein gezeigter Abschnitt: Ebene, Titel und ob er aufgeklappt ist. */
	record Section(Level level, String title, boolean expanded) {
	}

	// Vom Nutzer gewählter Zustand je Ebene; fehlt eine Ebene, gilt die Vorgabe
	private final Map<Level, Boolean> chosen = new EnumMap<>(Level.class);
	private final Map<Level, TitledPane> panes = new EnumMap<>(Level.class);
	// Verhindert, dass das Übertragen des Zustands als Wahl des Nutzers zählt
	private boolean applying = false;

	/** "Reihe A" bzw. "Reihe ohne Buchstaben". */
	static String rowName(TableRow row) {
		String label = row.getLabel() == null ? "" : row.getLabel().trim();
		return label.isEmpty() ? "Reihe ohne Buchstaben" : "Reihe " + label;
	}

	/**
	 * Die Abschnitte zur Markierung: die markierten Reihen (bei mehreren ein Abschnitt "n Reihen"), der Tisch und der Sitz
	 * des Tischs. Ein Sitz ohne Tisch zählt nicht.
	 */
	List<Section> sections(List<TableRow> rows, Table table, Seat seat) {
		Seat shownSeat = table == null ? null : seat;
		Level selected = shownSeat != null ? Level.SEAT : table != null ? Level.TABLE : rows.isEmpty() ? null : Level.ROW;
		List<Section> result = new ArrayList<>();
		if (!rows.isEmpty()) {
			result.add(section(Level.ROW, rows.size() == 1 ? rowName(rows.get(0)) : rows.size() + " Reihen", selected));
		}
		if (table != null) {
			result.add(section(Level.TABLE, table.getDisplayName(), selected));
		}
		if (shownSeat != null) {
			result.add(section(Level.SEAT, "Sitz " + shownSeat.getSeatNumber(), selected));
		}
		return result;
	}

	private Section section(Level level, String title, Level selected) {
		return new Section(level, title, chosen.getOrDefault(level, level == selected));
	}

	/** Der Nutzer hat den Abschnitt der Ebene auf- oder zugeklappt. */
	void toggled(Level level, boolean expanded) {
		chosen.put(level, expanded);
	}

	/** Verbindet eine Ebene mit ihrem Abschnitt im Panel; dessen Titel ist ein {@link Label} als Grafik. */
	void bind(Level level, TitledPane pane) {
		panes.put(level, pane);
		pane.expandedProperty().addListener((obs, wasExpanded, expanded) -> {
			if (!applying) {
				toggled(level, expanded);
			}
		});
	}

	/**
	 * Zeigt die Abschnitte zur Markierung mit Titel und Zustand, die übrigen verschwinden ganz.
	 *
	 * @return ob mindestens ein Abschnitt zu sehen ist
	 */
	boolean show(List<TableRow> rows, Table table, Seat seat) {
		List<Section> shown = sections(rows, table, seat);
		applying = true;
		try {
			panes.forEach((level, pane) -> {
				Section section = shown.stream().filter(s -> s.level() == level).findFirst().orElse(null);
				pane.setVisible(section != null);
				pane.setManaged(section != null);
				if (section != null) {
					((Label) pane.getGraphic()).setText(section.title());
					pane.setExpanded(section.expanded());
				}
			});
		} finally {
			applying = false;
		}
		return !shown.isEmpty();
	}
}
