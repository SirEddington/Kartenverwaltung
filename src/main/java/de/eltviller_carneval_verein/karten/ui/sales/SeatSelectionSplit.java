package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatStatus;

/**
 * Teilt eine Auswahl in freie Sitze und solche, die schon reserviert oder verkauft sind. Daran entscheidet sich, ob vor
 * dem Sammelverkauf nachgefragt werden muss und welche Antworten dort sinnvoll sind.
 */
public record SeatSelectionSplit(List<Seat> free, List<Seat> occupied) {

	public static SeatSelectionSplit of(List<Seat> seats) {
		return new SeatSelectionSplit(seats.stream().filter(seat -> seat.getStatus() == SeatStatus.FREE).toList(),
				seats.stream().filter(seat -> seat.getStatus() != SeatStatus.FREE).toList());
	}

	public boolean hasFree() {
		return !free.isEmpty();
	}

	public boolean hasOccupied() {
		return !occupied.isEmpty();
	}
}
