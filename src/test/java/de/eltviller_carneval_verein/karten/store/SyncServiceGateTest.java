package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.repository.ContentRules;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;

/** Der {@link SyncService} gibt die Inhaltsprüfung an beide Stores und den Index weiter und verlangt sie bei der App. */
class SyncServiceGateTest {

	private static final Instant START = Instant.parse("2026-10-26T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(START, ZoneOffset.UTC);
	private static final UnitKey BOOKING = UnitKey.booking("e1", "p1", "A-1");
	/** Ein fremder Rechner, der nur als Dateien vorkommt. */
	private static final String OTHER = "zzzzzzzzzzzzzzzzzzzzzzzzzz";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@TempDir
	Path dir;

	/** Eine Buchung des fremden Rechners mit einem Zahlungsstatus, den die Regeln dieser Fassung nicht kennen. */
	private static Version voucherBooking() {
		ObjectNode payload = MAPPER.createObjectNode().put("paymentStatus", "x-test-voucher");
		return new Version(StoreCodec.FORMAT, VersionId.create(START.toEpochMilli() - 1000, OTHER, 0), List.of(), OTHER,
				"Kasse", null, START, "fremd", false, payload);
	}

	@Test
	void localStoreUsesTheGate() throws IOException {
		Path appData = dir.resolve("a");
		Version foreign = voucherBooking();
		VersionStore plain = new VersionStore(appData.resolve("data"), new NioFileLayer());
		byte[] bytes = StoreCodec.write(foreign);
		assertTrue(plain.writeVersion(BOOKING, foreign.id(), bytes));
		assertTrue(plain.createClaim(BOOKING, foreign.claimName(),
				StoreCodec.write(new Claim(foreign.id(), StoreCodec.sha256(bytes), OTHER, START))));

		SyncService control = TestSyncServices.unstarted(dir.resolve("control"), "c", new NioFileLayer(), p -> true,
				CLOCK, ContentGate.NONE);
		SyncService gated = TestSyncServices.unstarted(appData, "a", new NioFileLayer(), p -> true, CLOCK);
		assertTrue(gated.startup(""));

		UnitState state = gated.unitState(BOOKING);
		assertEquals(Status.LOCKED_FORMAT, state.status());
		assertTrue(state.contentLocked());
		assertTrue(gated.snapshots().get(BOOKING).contentLocked());
		// ohne die Prüfung wäre dieselbe Datei gültig
		assertTrue(control.startup(""));
		assertFalse(control.unitState(BOOKING).contentLocked());
	}

	@Test
	void sharedStoreUsesTheGate() throws IOException {
		Path sharedDir = Files.createDirectories(dir.resolve("gemeinsam"));
		Version foreign = voucherBooking();
		VersionStore sharedStore = new VersionStore(sharedDir.resolve("data"), new NioFileLayer(), sharedDir, null);
		byte[] bytes = StoreCodec.write(foreign);
		assertTrue(sharedStore.writeVersion(BOOKING, foreign.id(), bytes));
		Feed feed = new Feed(StoreCodec.FORMAT, OTHER, 1, START,
				List.of(new FeedEntry(BOOKING.relativePath(), foreign.id(), foreign.claimName())));
		assertTrue(sharedStore.createRelative("feeds/" + OTHER + "/1.json", StoreCodec.write(feed)));
		assertTrue(sharedStore.createClaim(BOOKING, foreign.claimName(),
				StoreCodec.write(new Claim(foreign.id(), StoreCodec.sha256(bytes), OTHER, START))));

		SyncService gated = TestSyncServices.sharedMode(dir.resolve("a"), sharedDir, "a", CLOCK);
		TestSyncServices.tick(gated);

		UnitState state = gated.unitState(BOOKING);
		assertEquals(Status.LOCKED_FORMAT, state.status());
		assertTrue(state.contentLocked());
		// ohne die Prüfung wäre dieselbe Datei im gemeinsamen Ordner gültig
		SyncService control = TestSyncServices.unstarted(dir.resolve("control"), "c", new NioFileLayer(), p -> true,
				CLOCK, ContentGate.NONE);
		assertTrue(control.startup(sharedDir.toString()));
		TestSyncServices.tick(control);
		assertEquals(Status.OK, control.unitState(BOOKING).status());
		assertFalse(control.unitState(BOOKING).contentLocked());
	}

	@Test
	void getInstanceWithoutGateFails() {
		ContentGate previous = SyncService.installedContentGate();
		SyncService.restoreContentGate(null);
		try {
			IllegalStateException e = assertThrows(IllegalStateException.class, SyncService::getInstance);
			assertEquals("Inhaltsprüfung nicht eingerichtet", e.getMessage());
		} finally {
			SyncService.restoreContentGate(previous);
		}
	}

	@Test
	void installContentGateIsReadBack() {
		ContentGate previous = SyncService.installedContentGate();
		try {
			SyncService.restoreContentGate(null);
			assertNull(SyncService.installedContentGate());
			SyncService.installContentGate(ContentRules.GATE);
			assertSame(ContentRules.GATE, SyncService.installedContentGate());
			assertThrows(NullPointerException.class, () -> SyncService.installContentGate(null));
		} finally {
			SyncService.restoreContentGate(previous);
		}
	}
}
