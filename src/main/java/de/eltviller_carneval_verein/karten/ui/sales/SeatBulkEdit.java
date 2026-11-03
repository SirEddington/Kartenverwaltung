package de.eltviller_carneval_verein.karten.ui.sales;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.SaleDates;

/**
 * Sammelt die Eingaben des Sitz-Dialogs für eine Liste von Buchungen und überträgt sie erst mit {@link #apply()}.
 *
 * <p>
 * Jedes Feld kennt den {@link Field#common() gemeinsamen Wert} der Buchungen (leer, wenn sie sich unterscheiden: im
 * Dialog "verschieden") und merkt sich nur, was der Nutzer wirklich geändert hat. Alles andere bleibt je Buchung
 * erhalten. Der Preis ist der effektive Preis ({@link Booking#getEffectivePrice()}): Wer den Standardpreis der
 * Vorstellung eingibt, entfernt den Einzelpreis. Der Rollstuhlplatz gehört zur Bestuhlung und ist hier kein Feld. Die
 * Daten der Aktionen (Reservierung, Bezahlung, Abholung) lassen sich ändern und mit {@link Field#clear()} löschen; beim
 * Übernehmen trägt {@link SaleDates} den heutigen Tag für neu gesetzte Aktionen ein. Die
 * Klasse kennt kein JavaFX, damit sich die Regeln ohne Oberfläche testen lassen.
 */
public final class SeatBulkEdit {

	private final List<Booking> bookings;
	private final Field<String> lastName;
	private final Field<String> firstName;
	private final Field<PaymentStatus> paymentStatus;
	private final Field<Integer> price;
	private final Field<Integer> paidAmount;
	private final Field<Boolean> collected;
	private final Field<String> comment;
	private final Field<LocalDate> reservationDate;
	private final Field<LocalDate> paymentDate;
	private final Field<LocalDate> collectionDate;
	private final List<Field<?>> fields;
	/** Freie Buchungen mit Resten und ihr Stand ohne diese Reste (so zeigt sie der Dialog). */
	private final Map<Booking, Booking> leftoverViews = new IdentityHashMap<>();

	public SeatBulkEdit(List<Booking> bookings) {
		this.bookings = List.copyOf(bookings);
		for (Booking booking : this.bookings) {
			if (booking.hasLeftovers()) {
				Booking view = booking.copy();
				view.clearLeftovers();
				leftoverViews.put(booking, view);
			}
		}
		lastName = text(Booking::getLastName, Booking::setLastName);
		firstName = text(Booking::getFirstName, Booking::setFirstName);
		paymentStatus = new Field<>(shown(Booking::getPaymentStatus), Booking::setPaymentStatus, null);
		// Der Einzelpreis bleibt beim Verwerfen der Reste; der effektive Preis braucht die Vorstellung der Buchung selbst
		price = new Field<>(Booking::getEffectivePrice, Booking::setEffectivePrice, null);
		paidAmount = new Field<>(shown(Booking::getPaidCents), Booking::setPaidAmount, null);
		collected = new Field<>(shown(Booking::isCollected), Booking::setCollected, null);
		comment = text(Booking::getComment, Booking::setComment);
		reservationDate = new Field<>(shown(Booking::getReservationDate), Booking::setReservationDate, null);
		paymentDate = new Field<>(shown(Booking::getPaymentDate), Booking::setPaymentDate, null);
		collectionDate = new Field<>(shown(Booking::getCollectionDate), Booking::setCollectionDate, null);
		fields = List.of(lastName, firstName, paymentStatus, price, paidAmount, collected, comment, reservationDate, paymentDate,
				collectionDate);
	}

	/** Textfelder: Kein Text ({@code null}) und leerer Text gelten als gleicher Wert. */
	private Field<String> text(Function<Booking, String> getter, BiConsumer<Booking, String> setter) {
		Function<Booking, String> shownGetter = shown(getter);
		return new Field<>(booking -> Objects.requireNonNullElse(shownGetter.apply(booking), ""), setter, "");
	}

	/**
	 * Der Wert, den der Dialog zeigt: Bei einer freien Buchung mit Resten ({@link Booking#hasLeftovers()}) der Wert ohne
	 * diese Reste, denn sie werden beim Übernehmen verworfen (KARV-121).
	 */
	private <T> Function<Booking, T> shown(Function<Booking, T> getter) {
		return booking -> getter.apply(leftoverViews.getOrDefault(booking, booking));
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

	/**
	 * Gezahlter Betrag je bezahlter Buchung in Cent ({@link Booking#getPaidCents()}); offene Buchungen haben keinen, ihr
	 * Wert ist {@code null}. Eine Eingabe gilt nur für Buchungen, die nach dem Übernehmen bezahlt sind ({@link SaleDates});
	 * ohne Eingabe bekommt jede neu bezahlte Buchung ihren eigenen Preis.
	 */
	public Field<Integer> paidAmount() {
		return paidAmount;
	}

	public Field<Boolean> collected() {
		return collected;
	}

	public Field<String> comment() {
		return comment;
	}

	public Field<LocalDate> reservationDate() {
		return reservationDate;
	}

	public Field<LocalDate> paymentDate() {
		return paymentDate;
	}

	public Field<LocalDate> collectionDate() {
		return collectionDate;
	}

	/**
	 * Die Buchungen, deren Nachname durch das Übernehmen leer wird, die danach aber noch Daten trügen
	 * ({@link Booking#hasData()}). Für sie fragt der Dialog vor dem Übernehmen, ob storniert werden soll (KARV-121).
	 */
	public List<Booking> nameRemovalsLeavingData() {
		List<Booking> removals = new ArrayList<>();
		for (Booking booking : bookings) {
			if (booking.isReserved()) {
				Booking after = preview(booking);
				if (!after.isReserved() && after.hasData()) {
					removals.add(booking);
				}
			}
		}
		return removals;
	}

	/**
	 * Der Stand der Buchung nach dem Übernehmen, ohne sie zu ändern: eine Kopie mit ihrer Vorstellung (für Preis und
	 * gezahlten Betrag), ohne Reste und mit den Eingaben. Die heutigen Daten von {@link SaleDates} fehlen darin.
	 */
	public Booking preview(Booking booking) {
		Booking after = booking.copy();
		after.setParent(booking.getParent());
		if (leftoverViews.containsKey(booking)) {
			after.clearLeftovers();
		}
		fields.forEach(field -> field.applyTo(after));
		return after;
	}

	public boolean hasChanges() {
		return fields.stream().anyMatch(Field::changed);
	}

	/** Überträgt alle geänderten Felder auf jede Buchung der Liste; neu gesetzte Aktionen tragen den heutigen Tag ein. */
	public void apply() {
		apply(LocalDate.now());
	}

	/**
	 * Wie {@link #apply()}, mit einem festen "heute" (für Tests). Je Buchung laufen alle Felder in einem Schritt über
	 * {@link SaleDates#change}: Wechselt eine Aktion von "nein" auf "ja" (Name, Zahlung, abgeholt), trägt es das Datum ein,
	 * es sei denn, das Datum wird im selben Schritt geändert. Das gilt vor dem Tagesdatum. Eine freie Buchung mit Resten
	 * verliert diese zuerst ({@link Booking#clearLeftovers()}), wie der Dialog sie gezeigt hat.
	 */
	public void apply(LocalDate today) {
		for (Booking booking : bookings) {
			if (leftoverViews.containsKey(booking)) {
				booking.clearLeftovers();
			}
			SaleDates.change(booking, target -> fields.forEach(field -> field.applyTo(target)), today);
		}
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
		/** Das Feld wurde bewusst geleert (siehe {@link #clear()}); gilt für alle Buchungen als Wert {@code null}. */
		private boolean cleared;

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
			cleared = false;
		}

		/**
		 * Leert das Feld bewusst: Der Wert wird bei allen Buchungen {@code null}. Nötig, weil {@code null} in
		 * {@link #change} "nicht geändert" heißt (gedacht für Daten, die man löschen kann). Eine spätere
		 * {@link #change}-Eingabe nimmt das Leeren zurück.
		 */
		public void clear() {
			pending = null;
			cleared = true;
		}

		/** Ob alle Buchungen im Feld keinen Wert haben ({@code null}); bei Datumsfeldern "leer", nicht "verschieden". */
		public boolean allEmpty() {
			return bookings.stream().allMatch(booking -> getter.apply(booking) == null);
		}

		private boolean changed() {
			return pending != null || cleared;
		}

		private void applyTo(Booking booking) {
			if (cleared) {
				setter.accept(booking, null);
			} else if (pending != null) {
				setter.accept(booking, pending);
			}
		}
	}
}
