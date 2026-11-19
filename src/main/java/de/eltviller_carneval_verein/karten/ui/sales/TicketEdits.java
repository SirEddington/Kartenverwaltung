package de.eltviller_carneval_verein.karten.ui.sales;

import java.time.LocalDate;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import javafx.application.Platform;

/**
 * Wann die Kartentabelle neu aufbaut und welche Buchungen dabei trotz Suche und Filter stehen bleiben (KARV-193, Spec
 * Abschnitt 11).
 *
 * <p>Jeder Neuaufbau wartet, bis keine Zelle mehr bearbeitet wird: Eine Tab-Folge durch mehrere Zellen ordnet die
 * Tabelle erst nach ihrem Ende neu, und keine Zeile verschiebt sich unter der Bearbeitung. Eine Buchung, deren Wert die
 * Kassenkraft selbst geändert hat, bleibt sichtbar, bis Suche oder Filter sich ändern oder eine andere Vorstellung
 * gezeigt wird ({@link #endPinning}).
 */
final class TicketEdits {

	/**
	 * Die Werte einer Buchung, die der Sitz-Dialog ändern kann (dazu die E-Mail, die beim Verwerfen von Resten leer wird),
	 * roh wie gespeichert. Ein Vergleich vorher/nachher sagt, ob sich ein Wert tatsächlich geändert hat (Spec Abschnitt
	 * 11).
	 */
	record Values(String lastName, String firstName, String eMail, String comment, PaymentStatus paymentStatus, Integer paidAmount,
			Integer price, boolean collected, LocalDate reservationDate, LocalDate paymentDate, LocalDate collectionDate) {

		static Values of(Booking booking) {
			return new Values(booking.getLastName(), booking.getFirstName(), booking.getEMail(), booking.getComment(),
					booking.getPaymentStatus(), booking.getPaidAmount(), booking.getPrice(), booking.isCollected(),
					booking.getReservationDate(), booking.getPaymentDate(), booking.getCollectionDate());
		}
	}

	private final BooleanSupplier editing;
	private final Runnable rebuild;
	/** Selbst geänderte Buchungen, nach Identität. */
	private final Set<Booking> pinned = Collections.newSetFromMap(new IdentityHashMap<>());
	/** Ein Neuaufbau ist angefordert und noch nicht gelaufen. */
	private boolean pending;

	/**
	 * @param editing ob gerade eine Zelle bearbeitet wird
	 * @param rebuild baut die Tabelle neu auf
	 */
	TicketEdits(BooleanSupplier editing, Runnable rebuild) {
		this.editing = editing;
		this.rebuild = rebuild;
	}

	/**
	 * Eine eigene Änderung an der Buchung (Commit einer Zelle, Checkbox, Sitz-Dialog, Platz zuweisen). Nur mit
	 * {@code valueChanged} gilt sie als selbst geändert; neu aufgebaut wird in jedem Fall, aber erst nach dem laufenden
	 * Commit und nach dem Ende einer Bearbeitung.
	 */
	void changed(Booking booking, boolean valueChanged) {
		if (valueChanged) {
			pinned.add(booking);
		}
		pending = true;
		// Nicht mitten im Commit: Ein Neuaufbau ließe die Zelle (etwa die Auswahlliste) im Bearbeiten hängen
		Platform.runLater(this::flush);
	}

	/**
	 * Merkt die Buchung als selbst geändert, ohne einen Neuaufbau anzufordern: für Aufrufer, die gleich selbst neu
	 * aufbauen (Platz zuweisen), damit danach kein zweiter Durchgang folgt.
	 */
	void pin(Booking booking) {
		pinned.add(booking);
	}

	/** Ein Neuaufbau aus anderem Anlass (fremde Änderung, Suche, Filter, Sortierung): sofort, sonst nach der Bearbeitung. */
	void requestRebuild() {
		pending = true;
		flush();
	}

	/**
	 * Keine Zelle wird mehr bearbeitet. Geprüft wird erst danach: Tab übernimmt eine Zelle und bearbeitet gleich die
	 * nächste, dazwischen steht kurz keine Zelle im Bearbeiten.
	 */
	void editingEnded() {
		if (pending) {
			Platform.runLater(this::flush);
		}
	}

	/** Die selbst geänderten Buchungen (nach Identität). */
	Set<Booking> pinned() {
		return Collections.unmodifiableSet(pinned);
	}

	/** Beendet die Ausnahme für selbst geänderte Buchungen (Suche oder Filter geändert, andere Vorstellung). */
	void endPinning() {
		pinned.clear();
	}

	/** Die Zeilen, die {@code passes} bestehen oder deren Buchung selbst geändert ist, in ihrer Reihenfolge. */
	List<BookingDTO> visible(List<BookingDTO> rows, Predicate<BookingDTO> passes) {
		return rows.stream().filter(row -> pinned.contains(row.getBooking()) || passes.test(row)).toList();
	}

	private void flush() {
		if (!pending || editing.getAsBoolean()) {
			return;
		}
		pending = false;
		rebuild.run();
	}
}
