package de.eltviller_carneval_verein.karten.model;

import de.eltviller_carneval_verein.karten.ui.UiColors;

public enum SeatStatus {
	FREE("Frei", UiColors.SEAT_FREE), // Grün
	RESERVED("Reserviert", UiColors.SEAT_RESEREVED), // Gelb / Orange
	SOLD("Verkauft", UiColors.SEAT_SOLD), // Rot
	BLOCKED("Blockiert", UiColors.SEAT_BLOCKED); // Grau

	private final String displayName;
	private final UiColors seatColor;

	SeatStatus(String displayName, UiColors seatColor) {
		this.displayName = displayName;
		this.seatColor = seatColor;
	}

	public String getDisplayName() {
		return displayName;
	}

	public UiColors getSeatColor() {
		return seatColor;
	}
}
