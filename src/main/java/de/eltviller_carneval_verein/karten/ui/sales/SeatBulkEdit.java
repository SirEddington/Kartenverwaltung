package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;

/**
 * Sammelt die Eingaben des Sitz-Dialogs für eine Liste von Buchungen und überträgt sie erst mit {@link #apply()}.
 *
 * <p>
 * Jedes Feld kennt den {@link Field#common() gemeinsamen Wert} der Buchungen (leer, wenn sie sich unterscheiden: im
 * Dialog "verschieden") und merkt sich nur, was der Nutzer wirklich geändert hat. Alles andere bleibt je Buchung
 * erhalten. Der Preis ist der effektive Preis ({@link Booking#getEffectivePrice()}): Wer den Standardpreis der
 * Vorstellung eingibt, entfernt den Einzelpreis. Der Rollstuhlplatz gehört zur Bestuhlung und ist hier kein Feld. Die
 * Klasse kennt kein JavaFX, damit sich die Regeln ohne Oberfläche testen lassen.
 */
public final class SeatBulkEdit {

	private final List<Booking> bookings;
	private final Field<String> lastName;
	private final Field<String> firstName;
	private final Field<PaymentStatus> paymentStatus;
	private final Field<Integer> price;
	private final Field<Boolean> collected;
	private final Field<String> comment;
	private final List<Field<?>> fields;

	public SeatBulkEdit(List<Booking> bookings) {
		this.bookings = List.copyOf(bookings);
		lastName = text(Booking::getLastName, Booking::setLastName);
		firstName = text(Booking::getFirstName, Booking::setFirstName);
		paymentStatus = new Field<>(Booking::getPaymentStatus, Booking::setPaymentStatus, null);
		price = new Field<>(Booking::getEffectivePrice, Booking::setEffectivePrice, null);
		collected = new Field<>(Booking::isCollected, Booking::setCollected, null);
		comment = text(Booking::getComment, Booking::setComment);
		fields = List.of(lastName, firstName, paymentStatus, price, collected, comment);
	}

	/** Textfelder: Kein Text ({@code null}) und leerer Text gelten als gleicher Wert. */
	private Field<String> text(Function<Booking, String> getter, BiConsumer<Booking, String> setter) {
		return new Field<>(booking -> Objects.requireNonNullElse(getter.apply(booking), ""), setter, "");
	}

	public List<Booking> bookings() {
		return bookings;
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

	/** Effektiver Preis je Buchung in Cent. */
	public Field<Integer> price() {
		return price;
	}

	public Field<Boolean> collected() {
		return collected;
	}

	public Field<String> comment() {
		return comment;
	}

	public boolean hasChanges() {
		return fields.stream().anyMatch(Field::changed);
	}

	/** Überträgt alle geänderten Felder auf jede Buchung der Liste. */
	public void apply() {
		fields.forEach(Field::apply);
	}

	/**
	 * Gesamtpreis in Cent: Ist ein neuer Preis eingegeben, Anzahl mal dieser Preis (so sieht man vor dem Übernehmen, was
	 * die Auswahl kostet), sonst die Summe der bisherigen effektiven Preise.
	 */
	public long totalCents() {
		if (price.changed()) {
			return (long) bookings.size() * price.pending;
		}
		long sum = 0;
		for (Booking booking : bookings) {
			sum += booking.getEffectivePrice();
		}
		return sum;
	}

	/** Ein Feld des Dialogs über alle Buchungen der Liste. */
	public final class Field<T> {

		private final Function<Booking, T> getter;
		private final BiConsumer<Booking, T> setter;
		/** Eingabe, die "nichts eingegeben" bedeutet, solange die Buchungen verschiedene Werte haben (bei Texten ""). */
		private final T blank;
		private final Optional<T> common;
		private T pending;

		private Field(Function<Booking, T> getter, BiConsumer<Booking, T> setter, T blank) {
			this.getter = getter;
			this.setter = setter;
			this.blank = blank;
			this.common = commonValue();
		}

		private Optional<T> commonValue() {
			if (bookings.isEmpty()) {
				return Optional.empty();
			}
			T first = getter.apply(bookings.get(0));
			boolean allEqual = bookings.stream().allMatch(booking -> Objects.equals(first, getter.apply(booking)));
			return allEqual ? Optional.ofNullable(first) : Optional.empty();
		}

		/** Der Wert, den alle Buchungen haben; leer, wenn sie sich unterscheiden. */
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
				bookings.forEach(booking -> setter.accept(booking, pending));
			}
		}
	}
}
