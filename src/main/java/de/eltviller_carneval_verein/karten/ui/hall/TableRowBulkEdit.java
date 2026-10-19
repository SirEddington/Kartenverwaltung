package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.model.TableRow;

/**
 * Sammelt die Eingaben der Mehrfachbearbeitung für mehrere Tischreihen und überträgt sie erst mit {@link #apply()}
 * (Bauart wie {@code SeatBulkEdit}).
 *
 * <p>
 * Jedes Feld kennt den {@link Field#common() gemeinsamen Wert} der Reihen (leer, wenn sie sich unterscheiden) und merkt
 * sich nur, was der Nutzer wirklich geändert hat. Buchstabe und Position gehören je Reihe und sind kein Feld. Beim
 * Mittelgang steht 0 für "kein Gang" (im Modell {@code null}). Die Klasse kennt kein JavaFX.
 */
public final class TableRowBulkEdit {

	private final List<TableRow> rows;
	private final Field<Double> angle;
	private final Field<Double> tableRotation;
	private final Field<Double> tableGap;
	private final Field<Integer> aisleAfter;
	private final Field<Double> aisleWidth;
	private final List<Field<?>> fields;

	public TableRowBulkEdit(List<TableRow> rows) {
		this.rows = List.copyOf(rows);
		angle = new Field<>(TableRow::getAngle, TableRow::setAngle);
		tableRotation = new Field<>(TableRow::getTableRotation, TableRow::setTableRotation);
		tableGap = new Field<>(TableRow::getTableGap, TableRow::setTableGap);
		aisleAfter = new Field<>(TableRowBulkEdit::aisleAfterOf, TableRowBulkEdit::setAisleAfter);
		aisleWidth = new Field<>(TableRow::getAisleWidth, TableRow::setAisleWidth);
		fields = List.of(angle, tableRotation, tableGap, aisleAfter, aisleWidth);
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

	public boolean hasChanges() {
		return fields.stream().anyMatch(Field::changed);
	}

	/** Überträgt alle geänderten Felder auf jede Reihe der Liste. */
	public void apply() {
		fields.forEach(Field::apply);
	}

	/** Ein Feld der Mehrfachbearbeitung über alle Reihen der Liste. */
	public final class Field<T> {

		private final Function<TableRow, T> getter;
		private final BiConsumer<TableRow, T> setter;
		private final Optional<T> common;
		private T pending;

		private Field(Function<TableRow, T> getter, BiConsumer<TableRow, T> setter) {
			this.getter = getter;
			this.setter = setter;
			this.common = commonValue();
		}

		private Optional<T> commonValue() {
			if (rows.isEmpty()) {
				return Optional.empty();
			}
			T first = getter.apply(rows.get(0));
			boolean allEqual = rows.stream().allMatch(row -> Objects.equals(first, getter.apply(row)));
			return allEqual ? Optional.ofNullable(first) : Optional.empty();
		}

		/** Der Wert, den alle Reihen haben; leer, wenn sie sich unterscheiden. */
		public Optional<T> common() {
			return common;
		}

		/**
		 * Meldet den aktuellen Inhalt des Eingabefelds. {@code null} (Feld leer oder unbestimmt) sowie der bisherige
		 * gemeinsame Wert zählen als nicht geändert und nehmen eine frühere Eingabe zurück.
		 */
		public void change(T value) {
			boolean untouched = value == null || common.map(value::equals).orElse(false);
			pending = untouched ? null : value;
		}

		private boolean changed() {
			return pending != null;
		}

		private void apply() {
			if (changed()) {
				rows.forEach(row -> setter.accept(row, pending));
			}
		}
	}
}
