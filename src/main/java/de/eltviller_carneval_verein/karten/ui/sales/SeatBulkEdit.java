package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Seat;

/**
 * Sammelt die Eingaben des Sitz-Dialogs für eine Liste von Sitzen und überträgt sie erst mit {@link #apply()}.
 *
 * <p>
 * Jedes Feld kennt den {@link Field#common() gemeinsamen Wert} der Sitze (leer, wenn sie sich unterscheiden: im Dialog
 * "verschieden") und merkt sich nur, was der Nutzer wirklich geändert hat. Alles andere bleibt je Sitz erhalten. Die
 * Klasse kennt kein JavaFX, damit sich die Regeln ohne Oberfläche testen lassen.
 */
public final class SeatBulkEdit {

	private final List<Seat> seats;
	private final Field<String> lastName;
	private final Field<String> firstName;
	private final Field<PaymentStatus> paymentStatus;
	private final Field<Integer> price;
	private final Field<Boolean> collected;
	private final Field<Boolean> wheelchairAccessible;
	private final Field<String> comment;
	private final List<Field<?>> fields;

	public SeatBulkEdit(List<Seat> seats) {
		this.seats = List.copyOf(seats);
		lastName = text(Seat::getLastName, Seat::setLastName);
		firstName = text(Seat::getFirstName, Seat::setFirstName);
		paymentStatus = new Field<>(Seat::getPaymentStatus, Seat::setPaymentStatus, null);
		price = new Field<>(Seat::getPrice, Seat::setPrice, null);
		collected = new Field<>(Seat::isCollected, Seat::setCollected, null);
		wheelchairAccessible = new Field<>(Seat::isWheelchairAccessible, Seat::setWheelchairAccessible, null);
		comment = text(Seat::getComment, Seat::setComment);
		fields = List.of(lastName, firstName, paymentStatus, price, collected, wheelchairAccessible, comment);
	}

	/** Textfelder: Kein Text ({@code null}) und leerer Text gelten als gleicher Wert. */
	private Field<String> text(Function<Seat, String> getter, BiConsumer<Seat, String> setter) {
		return new Field<>(seat -> Objects.requireNonNullElse(getter.apply(seat), ""), setter, "");
	}

	public List<Seat> seats() {
		return seats;
	}

	public Field<String> lastName() {
		return lastName;
	}

	public Field<String> firstName() {
		return firstName;
	}

	public Field<PaymentStatus> paymentStatus() {
		return paymentStatus;
	}

	/** Preis je Sitz in Cent. */
	public Field<Integer> price() {
		return price;
	}

	public Field<Boolean> collected() {
		return collected;
	}

	public Field<Boolean> wheelchairAccessible() {
		return wheelchairAccessible;
	}

	public Field<String> comment() {
		return comment;
	}

	public boolean hasChanges() {
		return fields.stream().anyMatch(Field::changed);
	}

	/** Überträgt alle geänderten Felder auf jeden Sitz der Liste. */
	public void apply() {
		fields.forEach(Field::apply);
	}

	/**
	 * Gesamtpreis in Cent: Ist ein neuer Preis eingegeben, Sitzanzahl mal dieser Preis (so sieht man vor dem Übernehmen,
	 * was die Auswahl kostet), sonst die Summe der bisherigen Einzelpreise.
	 */
	public long totalCents() {
		if (price.changed()) {
			return (long) seats.size() * price.pending;
		}
		long sum = 0;
		for (Seat seat : seats) {
			sum += seat.getPrice();
		}
		return sum;
	}

	/** Ein Feld des Dialogs über alle Sitze der Liste. */
	public final class Field<T> {

		private final Function<Seat, T> getter;
		private final BiConsumer<Seat, T> setter;
		/** Eingabe, die "nichts eingegeben" bedeutet, solange die Sitze verschiedene Werte haben (bei Texten ""). */
		private final T blank;
		private final Optional<T> common;
		private T pending;

		private Field(Function<Seat, T> getter, BiConsumer<Seat, T> setter, T blank) {
			this.getter = getter;
			this.setter = setter;
			this.blank = blank;
			this.common = commonValue();
		}

		private Optional<T> commonValue() {
			if (seats.isEmpty()) {
				return Optional.empty();
			}
			T first = getter.apply(seats.get(0));
			boolean allEqual = seats.stream().allMatch(seat -> Objects.equals(first, getter.apply(seat)));
			return allEqual ? Optional.ofNullable(first) : Optional.empty();
		}

		/** Der Wert, den alle Sitze haben; leer, wenn sie sich unterscheiden. */
		public Optional<T> common() {
			return common;
		}

		/**
		 * Meldet den aktuellen Inhalt des Dialogfelds. {@code null} (Feld leer oder unbestimmt) sowie der bisherige
		 * gemeinsame Wert zählen als nicht geändert und nehmen eine frühere Eingabe zurück.
		 */
		public void change(T value) {
			boolean untouched = value == null || common.map(value::equals).orElse(false) || (common.isEmpty() && value.equals(blank));
			pending = untouched ? null : value;
		}

		private boolean changed() {
			return pending != null;
		}

		private void apply() {
			if (changed()) {
				seats.forEach(seat -> setter.accept(seat, pending));
			}
		}
	}
}
