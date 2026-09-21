package de.eltviller_carneval_verein.karten.model;

public enum SeatStatus {
	FREE("Frei"),
	RESERVED("Reserviert"),
	SOLD("Verkauft"),
	BLOCKED("Blockiert");

	private final String displayName;

	SeatStatus(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return displayName;
	}
}
