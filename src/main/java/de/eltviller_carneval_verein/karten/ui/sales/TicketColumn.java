package de.eltviller_carneval_verein.karten.ui.sales;

import java.text.Collator;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/**
 * Die Spalten der Kartentabelle als Werte, ohne JavaFX: Überschrift, Gruppenschlüssel einer Buchung, Text eines
 * Schlüssels (für Gruppenkopf und Spaltenfilter) und Ordnung der Schlüssel. Die Reihenfolge der Einträge ist die der
 * Spalten im FXML; der Name eines Eintrags steht in {@code settings.json} und bleibt stabil.
 */
public enum TicketColumn {

	PRESENTATION("Vorstellung", row -> row.getPresentation().getId(),
			(key, first) -> EntityLabels.nameOrPlaceholder(first.getPresentation().getName()), TicketColumn::presentationKeyOrder),
	TABLE("Tisch", row -> TableLabel.of(row.getBooking()), (key, first) -> key.toString(), natural(TableLabel.class)),
	CATEGORY("Kategorie", row -> textKey(row.getBooking().getCategory()), text(Booking::getCategory), textOrder()),
	SEAT("Sitz", row -> row.getBooking().getSeatNumber() == 0 ? Missing.VALUE : (Object) row.getBooking().getSeatNumber(),
			(key, first) -> key.toString(), natural(Integer.class)),
	LAST_NAME("Nachname", row -> textKey(row.getBooking().getLastName()), text(Booking::getLastName), textOrder()),
	FIRST_NAME("Vorname", row -> textKey(row.getBooking().getFirstName()), text(Booking::getFirstName), textOrder()),
	RESERVED("Reserviert", row -> row.getBooking().isReserved(), (key, first) -> yesNo(key), yesBeforeNo()),
	RESERVED_ON("Reserviert am", row -> dateKey(row.getBooking().getReservationDate()), (key, first) -> dateText(key),
			natural(LocalDate.class)),
	PRICE("Preis (€)", row -> (Object) row.getBooking().getEffectivePrice(), (key, first) -> centsText(key), natural(Integer.class)),
	PAID_AMOUNT("Bezahlt (€)", row -> row.getBooking().getPaidCents() == null ? Missing.VALUE : (Object) row.getBooking().getPaidCents(),
			(key, first) -> centsText(key), natural(Integer.class)),
	PAYMENT_STATUS("Zahlungsstatus", row -> row.getBooking().getPaymentStatus() == null ? Missing.VALUE : (Object) row.getBooking().getPaymentStatus(),
			(key, first) -> ((PaymentStatus) key).getDisplayName(), natural(PaymentStatus.class)),
	PAID_ON("Bezahlt am", row -> dateKey(row.getBooking().getPaymentDate()), (key, first) -> dateText(key), natural(LocalDate.class)),
	COLLECTED("Abgeholt", row -> row.getBooking().isCollected(), (key, first) -> yesNo(key), yesBeforeNo()),
	COLLECTED_ON("Abgeholt am", row -> dateKey(row.getBooking().getCollectionDate()), (key, first) -> dateText(key),
			natural(LocalDate.class)),
	WHEELCHAIR("Rollstuhl", row -> row.getBooking().isWheelchairAccessible(), (key, first) -> yesNo(key), yesBeforeNo()),
	COMMENT("Kommentar", row -> textKey(row.getBooking().getComment()), text(Booking::getComment), textOrder());

	/** Text eines fehlenden Werts. */
	private static final String EMPTY_TEXT = "(leer)";

	/** Schlüssel für einen fehlenden Wert (kein Text, keine Zahl, kein Datum); steht in jeder Ordnung zuletzt. */
	public static final Object EMPTY = Missing.VALUE;

	/** Hält den Schlüssel, damit die Konstanten oben ihn schon beim Anlegen kennen (kein Vorwärtsbezug auf ein Feld des Enums). */
	private static final class Missing {
		static final Object VALUE = new Object() {
			@Override
			public String toString() {
				return "(leer)";
			}
		};
	}

	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

	private final String header;
	private final Function<BookingDTO, Object> keyFunction;
	private final BiFunction<Object, BookingDTO, String> textFunction;
	private final Function<List<Presentation>, Comparator<Object>> orderFunction;

	TicketColumn(String header, Function<BookingDTO, Object> keyFunction, BiFunction<Object, BookingDTO, String> textFunction,
			Function<List<Presentation>, Comparator<Object>> orderFunction) {
		this.header = header;
		this.keyFunction = keyFunction;
		this.textFunction = textFunction;
		this.orderFunction = orderFunction;
	}

	/** Überschrift der Spalte, wie im FXML. */
	public String header() {
		return header;
	}

	/** Der Gruppenschlüssel der Buchung in dieser Spalte, bei einem fehlenden Wert {@link #EMPTY}. */
	public Object keyOf(BookingDTO row) {
		return keyFunction.apply(row);
	}

	/**
	 * Der Text eines Schlüssels für Gruppenkopf und Spaltenfilter. {@code first} ist die erste Buchung der Gruppe in der
	 * {@link #baseOrder Grundordnung}; Textspalten zeigen ihren Text ohne Rand.
	 */
	public String textOf(Object key, BookingDTO first) {
		return key == EMPTY ? EMPTY_TEXT : textFunction.apply(key, first);
	}

	/** Die Ordnung der Schlüssel dieser Spalte, {@link #EMPTY} zuletzt. */
	public Comparator<Object> keyOrder(List<Presentation> presentationOrder) {
		return orderFunction.apply(presentationOrder);
	}

	/** Die Grundordnung der Buchungen: Reihenfolge der Vorstellungen, darin {@link TableLabel#BOOKING_ORDER}. */
	public static Comparator<BookingDTO> baseOrder(List<Presentation> presentationOrder) {
		Map<String, Integer> positions = positions(presentationOrder);
		return Comparator.comparingInt((BookingDTO row) -> positions.getOrDefault(row.getPresentation().getId(), Integer.MAX_VALUE))
				.thenComparing(BookingDTO::getBooking, TableLabel.BOOKING_ORDER);
	}

	private static Map<String, Integer> positions(List<Presentation> presentationOrder) {
		Map<String, Integer> positions = new HashMap<>();
		for (int i = 0; i < presentationOrder.size(); i++) {
			positions.putIfAbsent(presentationOrder.get(i).getId(), i);
		}
		return positions;
	}

	private static Comparator<Object> presentationKeyOrder(List<Presentation> presentationOrder) {
		Map<String, Integer> positions = positions(presentationOrder);
		return emptyLast(Comparator.comparingInt(key -> positions.getOrDefault(key, Integer.MAX_VALUE)));
	}

	private static Object textKey(String text) {
		if (text == null || text.isBlank()) {
			return EMPTY;
		}
		return text.strip().toLowerCase(Locale.GERMAN);
	}

	private static BiFunction<Object, BookingDTO, String> text(Function<Booking, String> getter) {
		return (key, first) -> getter.apply(first.getBooking()).strip();
	}

	private static Function<List<Presentation>, Comparator<Object>> textOrder() {
		Collator collator = Collator.getInstance(Locale.GERMAN);
		Comparator<Object> order = emptyLast((a, b) -> collator.compare((String) a, (String) b));
		return presentationOrder -> order;
	}

	private static Object dateKey(LocalDate date) {
		return date == null ? EMPTY : date;
	}

	private static String dateText(Object key) {
		return DATE_FORMAT.format((LocalDate) key);
	}

	private static String centsText(Object key) {
		return MoneyFormat.formatCents((Integer) key);
	}

	private static String yesNo(Object key) {
		return (Boolean) key ? "Ja" : "Nein";
	}

	private static Function<List<Presentation>, Comparator<Object>> yesBeforeNo() {
		Comparator<Object> order = emptyLast((a, b) -> Boolean.compare((Boolean) b, (Boolean) a));
		return presentationOrder -> order;
	}

	private static <T extends Comparable<? super T>> Function<List<Presentation>, Comparator<Object>> natural(Class<T> type) {
		Comparator<Object> order = emptyLast((a, b) -> type.cast(a).compareTo(type.cast(b)));
		return presentationOrder -> order;
	}

	private static Comparator<Object> emptyLast(Comparator<Object> order) {
		return (a, b) -> {
			if (a == EMPTY) {
				return b == EMPTY ? 0 : 1;
			}
			return b == EMPTY ? -1 : order.compare(a, b);
		};
	}
}
