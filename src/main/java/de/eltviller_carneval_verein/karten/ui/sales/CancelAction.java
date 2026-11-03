package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.ArrayList;
import java.util.List;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingCancellation;
import de.eltviller_carneval_verein.karten.model.BookingCancellation.Summary;
import de.eltviller_carneval_verein.karten.ui.ConfirmDialog;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import javafx.stage.Window;

/**
 * Der Menüpunkt "Stornieren" für Kartentabelle und Saalansicht (KARV-121): Rückfrage, dann
 * {@link BookingCancellation#cancel}. Er gilt wie "Verkaufen" für die Auswahl; Buchungen ohne Daten zählen nicht.
 */
final class CancelAction {

	private CancelAction() {
	}

	/** Beschriftung des Menüeintrags (für eine Buchung ohne Anzahl). */
	static String menuText(int count) {
		return count == 1 ? "Buchung stornieren…" : "Ausgewählte Buchungen stornieren… (" + count + ")";
	}

	/** Ob es unter den Buchungen etwas zu stornieren gibt; sonst ist der Menüeintrag gesperrt. */
	static boolean anyWithData(List<Booking> bookings) {
		return bookings.stream().anyMatch(Booking::hasData);
	}

	/**
	 * Fragt nach und storniert dann die Buchungen mit Daten. Bei "sofort speichern" wird gleich gespeichert, wie nach
	 * dem Verkaufsdialog.
	 *
	 * @return {@code true}, wenn storniert wurde; die Ansicht frischt sich dann auf
	 */
	static boolean run(List<Booking> bookings, Window owner) {
		List<Booking> withData = bookings.stream().filter(Booking::hasData).toList();
		if (withData.isEmpty() || !ConfirmDialog.confirm(withData.size() == 1 ? "Buchung stornieren" : "Buchungen stornieren",
				question(withData), "Stornieren", "button-danger", owner)) {
			return false;
		}
		BookingCancellation.cancel(withData);
		if (BookingAutoSave.settingOn()) {
			BookingAutoSave.flushActive();
		}
		return true;
	}

	/** Die Rückfrage samt Hinweis auf Zahlung und Abholung ({@link #paymentHint}). */
	static String question(List<Booking> bookings) {
		List<Booking> withData = bookings.stream().filter(Booking::hasData).toList();
		String text;
		if (withData.size() == 1) {
			Booking booking = withData.get(0);
			String customer = EntityLabels.customer(booking);
			String place = booking.isUnassigned() ? "ohne Platz" : booking.getPlaceLabel();
			text = "Buchung " + (customer.isEmpty() ? "" : "von " + customer + " ") + "(" + place + ") stornieren?\n"
					+ "Name, Zahlung und Kommentar werden gelöscht, der Platz ist wieder frei.";
		} else {
			text = withData.size() + " Buchungen stornieren?\n"
					+ "Name, Zahlung und Kommentar werden gelöscht, die Plätze sind wieder frei.";
		}
		text += " Zurück nur über die Sicherungen des Events.";
		String hint = paymentHint(withData);
		return hint.isEmpty() ? text : text + "\n" + hint;
	}

	/**
	 * Der Hinweis auf bezahlte und abgeholte Buchungen, etwa "Davon 2 bezahlt (zusammen 45,00 €) und 1 abgeholt – an die
	 * Rückzahlung denken."; leer, wenn keine bezahlt oder abgeholt ist. Auch für die Rückfrage beim Löschen des Namens.
	 */
	static String paymentHint(List<Booking> bookings) {
		Summary summary = Summary.of(bookings);
		boolean single = summary.count() == 1;
		List<String> parts = new ArrayList<>();
		if (summary.paid() > 0) {
			String amount = MoneyFormat.formatCents(summary.paidCents());
			parts.add(single ? "bezahlt (" + amount + ")" : summary.paid() + " bezahlt (zusammen " + amount + ")");
		}
		if (summary.collected() > 0) {
			parts.add(single ? "abgeholt" : summary.collected() + " abgeholt");
		}
		if (parts.isEmpty()) {
			return "";
		}
		return (single ? "Die Buchung ist " : "Davon ") + String.join(" und ", parts)
				+ (summary.paid() > 0 ? " – an die Rückzahlung denken." : ".");
	}
}
