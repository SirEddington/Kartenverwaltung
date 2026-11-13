package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.model.TextAlignH;
import de.eltviller_carneval_verein.karten.model.TextAlignV;

/**
 * Sammelt die Eingaben der Mehrfachbearbeitung für mehrere Tischreihen und überträgt sie erst mit {@link #apply()}
 * (Bauart wie {@code SeatBulkEdit}).
 *
 * <p>
 * Jedes Feld kennt den {@link Field#common() gemeinsamen Wert} seiner Ziele (leer, wenn sie sich unterscheiden oder es
 * keine gibt) und merkt sich nur, was der Nutzer wirklich geändert hat. Ziele sind die Reihen selbst (Winkel,
 * Tischdrehung, Abstand, Mittelgang), alle Tische der Reihen ({@link #tables()}: Maße und Beschriftung, auch Tische mit
 * manueller Position) oder deren Sitze (Durchmesser; Tische ohne Sitze zählen dort nicht). Buchstabe und Position gehören
 * je Reihe und sind kein Feld. Beim Mittelgang steht 0 für "kein Gang" (im Modell {@code null}). Die Klasse kennt kein
 * JavaFX.
 */
public final class TableRowBulkEdit {

	private final List<TableRow> rows;
	private final List<Table> tables;
	private final List<Seat> seats;
	private final Field<Double> angle;
	private final Field<Double> tableRotation;
	private final Field<Double> tableGap;
	private final Field<Integer> aisleAfter;
	private final Field<Double> aisleWidth;
	private final Field<Double> tableWidth;
	private final Field<Double> tableHeight;
	private final Field<Double> seatWidth;
	private final Field<Boolean> textAuto;
	private final Field<Double> textSize;
	private final Field<TextAlignH> textAlignH;
	private final Field<TextAlignV> textAlignV;
	private final Field<Double> textRotation;
	private final List<Field<?>> fields;

	public TableRowBulkEdit(List<TableRow> rows) {
		this.rows = List.copyOf(rows);
		this.tables = tablesOf(this.rows);
		this.seats = tables.stream().flatMap(table -> table.getSeats().stream()).toList();
		angle = new Field<>(this.rows, TableRow::getAngle, TableRow::setAngle);
		tableRotation = new Field<>(this.rows, TableRow::getTableRotation, TableRow::setTableRotation);
		tableGap = new Field<>(this.rows, TableRow::getTableGap, TableRow::setTableGap);
		aisleAfter = new Field<>(this.rows, TableRowBulkEdit::aisleAfterOf, TableRowBulkEdit::setAisleAfter);
		aisleWidth = new Field<>(this.rows, TableRow::getAisleWidth, TableRow::setAisleWidth);
		tableWidth = new Field<>(tables, Table::getWidth, Table::setWidth);
		tableHeight = new Field<>(tables, Table::getHeight, Table::setHeight);
		// Sitze sind Kreise: Die Breite ist der Durchmesser
		seatWidth = new Field<>(seats, Seat::getWidth, Seat::setWidth);
		textAuto = new Field<>(tables, Table::isTextAuto, Table::setTextAuto);
		textSize = new Field<>(tables, Table::getTextSize, Table::setTextSize);
		textAlignH = new Field<>(tables, Table::getTextAlignH, Table::setTextAlignH);
		textAlignV = new Field<>(tables, Table::getTextAlignV, Table::setTextAlignV);
		textRotation = new Field<>(tables, Table::getTextRotation, Table::setTextRotation);
		fields = List.of(angle, tableRotation, tableGap, aisleAfter, aisleWidth, tableWidth, tableHeight, seatWidth, textAuto, textSize, textAlignH,
				textAlignV, textRotation);
	}

	/** Die Tische der Reihen, Reihe für Reihe in der Reihenfolge von {@link SeatingPlan#getTablesOf}. */
	public static List<Table> tablesOf(List<TableRow> rows) {
		return rows.stream().flatMap(row -> row.getParent() == null ? Stream.<Table>empty() : row.getParent().getTablesOf(row).stream()).toList();
	}

	/** Der Mittelgang als Zahl für die Eingabe: 0 heißt "kein Gang". */
	public static int aisleAfterOf(TableRow row) {
		return row.getAisleAfter() == null ? 0 : row.getAisleAfter();
	}

	/** Schreibt die Eingabe des Mittelgangs ins Modell: 0 entfernt den Gang. */
	public static void setAisleAfter(TableRow row, int value) {
		row.setAisleAfter(value == 0 ? null : value);
	}

	public List<TableRow> rows() {
		return rows;
	}

	/** Die Tische aller Reihen ({@link #tablesOf}). */
	public List<Table> tables() {
		return tables;
	}

	/** Die Sitze dieser Tische, Tisch für Tisch. */
	public List<Seat> seats() {
		return seats;
	}

	public Field<Double> angle() {
		return angle;
	}

	public Field<Double> tableRotation() {
		return tableRotation;
	}

	public Field<Double> tableGap() {
		return tableGap;
	}

	/** Mittelgang nach so vielen Tischen, 0 = kein Gang. */
	public Field<Integer> aisleAfter() {
		return aisleAfter;
	}

	public Field<Double> aisleWidth() {
		return aisleWidth;
	}

	public Field<Double> tableWidth() {
		return tableWidth;
	}

	public Field<Double> tableHeight() {
		return tableHeight;
	}

	/** Durchmesser aller Sitze der Tische. */
	public Field<Double> seatWidth() {
		return seatWidth;
	}

	public Field<Boolean> textAuto() {
		return textAuto;
	}

	public Field<Double> textSize() {
		return textSize;
	}

	public Field<TextAlignH> textAlignH() {
		return textAlignH;
	}

	public Field<TextAlignV> textAlignV() {
		return textAlignV;
	}

	public Field<Double> textRotation() {
		return textRotation;
	}

	public boolean hasChanges() {
		return fields.stream().anyMatch(Field::changed);
	}

	/** Überträgt alle geänderten Felder auf jedes ihrer Ziele. */
	public void apply() {
		fields.forEach(Field::apply);
	}

	/** Ein Feld der Mehrfachbearbeitung über alle seine Ziele (Reihen, Tische oder Sitze). */
	public static final class Field<T> {

		private final Optional<T> common;
		private final boolean hasTargets;
		private final Consumer<T> writer;
		private T pending;

		private <E> Field(List<E> targets, Function<E, T> getter, BiConsumer<E, T> setter) {
			this.common = commonValue(targets, getter);
			this.hasTargets = !targets.isEmpty();
			this.writer = value -> targets.forEach(target -> setter.accept(target, value));
		}

		private static <E, T> Optional<T> commonValue(List<E> targets, Function<E, T> getter) {
			if (targets.isEmpty()) {
				return Optional.empty();
			}
			T first = getter.apply(targets.get(0));
			boolean allEqual = targets.stream().allMatch(target -> Objects.equals(first, getter.apply(target)));
			return allEqual ? Optional.ofNullable(first) : Optional.empty();
		}

		/** Der Wert, den alle Ziele haben; leer, wenn sie sich unterscheiden oder es keine gibt. */
		public Optional<T> common() {
			return common;
		}

		/** Ob es Ziele gibt und sie sich unterscheiden (Hinweis "verschieden" an der Beschriftung). */
		public boolean mixed() {
			return hasTargets && common.isEmpty();
		}

		/**
		 * Meldet den aktuellen Inhalt des Eingabefelds. {@code null} (Feld leer oder unbestimmt) sowie der bisherige
		 * gemeinsame Wert zählen als nicht geändert und nehmen eine frühere Eingabe zurück; ohne Ziele ändert sich nichts.
		 */
		public void change(T value) {
			boolean untouched = !hasTargets || value == null || common.map(value::equals).orElse(false);
			pending = untouched ? null : value;
		}

		private boolean changed() {
			return pending != null;
		}

		private void apply() {
			if (changed()) {
				writer.accept(pending);
			}
		}
	}
}
