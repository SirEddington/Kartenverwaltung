package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.SeatStatus;

/**
 * Teilt eine Auswahl in freie Plätze und solche, die schon reserviert oder verkauft sind. Daran entscheidet sich, ob vor
 * dem Sammelverkauf nachgefragt werden muss und welche Antworten dort sinnvoll sind.
 */
public record SeatSelectionSplit(List<Booking> free, List<Booking> occupied) {

	public static SeatSelectionSplit of(List<Booking> bookings) {
		return new SeatSelectionSplit(bookings.stream().filter(booking -> booking.getStatus() == SeatStatus.FREE).toList(),
				bookings.stream().filter(booking -> booking.getStatus() != SeatStatus.FREE).toList());
	}

	public boolean hasFree() {
		return !free.isEmpty();
	}

	public boolean hasOccupied() {
		return !occupied.isEmpty();
	}
}
