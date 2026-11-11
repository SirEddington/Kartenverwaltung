package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SeatTest {

	private static Seat seat(double width) {
		Seat seat = new Seat();
		seat.setWidth(width);
		return seat;
	}

	@Test
	void radiusIsHalfTheWidth() {
		assertEquals(22.5, seat(45).radius(), 1e-9);
	}

	@Test
	void seatWithoutWidthHasFallback() {
		assertEquals(Seat.FALLBACK_RADIUS, seat(0).radius(), 1e-9);
	}

	@Test
	void seatWithNaNWidthHasFallback() {
		assertEquals(10, seat(Double.NaN).radius(), 1e-9);
	}
}
