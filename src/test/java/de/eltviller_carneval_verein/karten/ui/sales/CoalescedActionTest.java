package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class CoalescedActionTest {

	private final List<Runnable> scheduled = new ArrayList<>();
	private int runs = 0;
	private final CoalescedAction action = new CoalescedAction(scheduled::add, () -> runs++);

	private void runScheduled() {
		List<Runnable> toRun = new ArrayList<>(scheduled);
		scheduled.clear();
		toRun.forEach(Runnable::run);
	}

	@Test
	void manyRequestsScheduleTheActionOnlyOnce() {
		for (int i = 0; i < 500; i++) {
			action.request();
		}

		assertEquals(1, scheduled.size());
		assertEquals(0, runs);
	}

	@Test
	void theActionRunsOnceForManyRequests() {
		for (int i = 0; i < 500; i++) {
			action.request();
		}

		runScheduled();

		assertEquals(1, runs);
	}

	@Test
	void aRequestAfterTheRunSchedulesAgain() {
		action.request();
		runScheduled();

		action.request();
		action.request();

		assertEquals(1, scheduled.size());
		runScheduled();
		assertEquals(2, runs);
	}
}
