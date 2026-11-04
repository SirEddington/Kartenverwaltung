package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.store.ConnectMode;
import de.eltviller_carneval_verein.karten.store.ConnectOutcome;
import de.eltviller_carneval_verein.karten.store.ConnectOutcome.Kind;
import de.eltviller_carneval_verein.karten.store.SyncState;

/** Hält fest, dass das Test-Doppel ohne FX-Thread arbeitet und genau die vereinbarten Aufrufe aufzeichnet. */
class FakeConnectionTest {

	@Test
	void propertiesNotifyObserversAndCallsAreRecorded() {
		FakeConnection fake = new FakeConnection();
		List<SyncState> seen = new ArrayList<>();
		fake.syncState().addListener((obs, old, now) -> seen.add(now));

		fake.state(SyncState.OFFLINE).progress(0.5).marker("X", false);
		fake.startup(null, p -> {
		});
		CompletableFuture<ConnectOutcome> connecting = fake.connect("X", ConnectMode.ASK);
		fake.decideConnect(ConnectMode.MERGE);
		fake.disconnect();
		fake.syncNow();

		assertEquals(List.of(SyncState.OFFLINE), seen);
		assertEquals(0.5, fake.fullSyncProgress().get());
		assertFalse(fake.connectionComplete().get());
		assertEquals(Optional.of("X"), fake.connectedDir());
		assertEquals(List.of("startup:null", "connect:X:ASK", "decide:MERGE", "disconnect", "syncNow"), fake.calls());
		assertFalse(connecting.isDone());

		ConnectOutcome done = new ConnectOutcome(Kind.AKTUELL, false, null, null);
		fake.outcome(done);

		assertTrue(connecting.isDone());
		assertEquals(done, connecting.join());
	}
}
