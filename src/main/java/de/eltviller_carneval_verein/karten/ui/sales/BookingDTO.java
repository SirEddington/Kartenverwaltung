package de.eltviller_carneval_verein.karten.ui.sales;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;

/** Eine Zeile der Kartentabelle: die Buchung mit ihrer Vorstellung und ihrem Event. */
public class BookingDTO {
	private final Event event;
	private final Presentation presentation;
	private final Booking booking;

	public BookingDTO(Event event, Presentation presentation, Booking booking) {
		this.event = event;
		this.presentation = presentation;
		this.booking = booking;
	}

	public Event getEvent() {
		return event;
	}

	public Presentation getPresentation() {
		return presentation;
	}

	public Booking getBooking() {
		return booking;
	}
}
