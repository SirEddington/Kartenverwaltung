package de.eltviller_carneval_verein.karten.ui.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.TestRepositories;
import de.eltviller_carneval_verein.karten.store.ForeignVersions;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.ui.accounting.LockedBookingsNotice.Counts;
import de.eltviller_carneval_verein.karten.ui.sales.LockedSeats;
import javafx.application.Platform;

/** Der Hinweis der Abrechnung auf gesperrte Buchungen (KARV-135). */
class LockedBookingsNoticeTest {

	@TempDir
	Path appData;

	@BeforeAll
	static void startFx() {
		try {
			Platform.startup(() -> {
			});
		} catch (IllegalStateException alreadyStarted) {
			// der Toolkit läuft schon (anderer Test im selben Lauf)
		}
		Platform.setImplicitExit(false);
	}

	@AfterEach
	void uninstallLockedSeats() {
		// wie ohne Einrichtung: nichts gesperrt, der Link öffnet nichts
		LockedSeats.install(Set::of, () -> {
		});
	}

	private static <T> T onFx(Supplier<T> action) throws Exception {
		CompletableFuture<T> result = new CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				result.complete(action.get());
			} catch (Throwable e) {
				result.completeExceptionally(e);
			}
		});
		return result.get(10, TimeUnit.SECONDS);
	}

	@Test
	void noLockedBookingsGivesNoText() {
		assertEquals("", LockedBookingsNotice.text(0, 0));
	}

	@Test
	void missingAndOlderAreCountedSeparately() {
		assertEquals("2 gesperrte Buchungen sind nicht berücksichtigt · 1 Buchung ist mit einem älteren Stand gezählt",
				LockedBookingsNotice.text(2, 1));
	}

	@Test
	void singleAndPluralFormsStandAlone() {
		assertEquals("1 gesperrte Buchung ist nicht berücksichtigt", LockedBookingsNotice.text(1, 0));
		assertEquals("3 Buchungen sind mit einem älteren Stand gezählt", LockedBookingsNotice.text(0, 3));
	}

	/**
	 * Gezählt wird am Stand des Versionsspeichers ({@code lockedUnder}), nicht an der Liste der gesperrten Einträge (die
	 * hier nicht eingerichtet, also leer ist): Müller (inhaltlich zu neu) fehlt im Modell, Meier (beschädigt nach einem
	 * lesbaren Stand) wird mit dem älteren Stand gezählt. "Alle" zählt die Buchungen aller Vorstellungen.
	 */
	@Test
	void countsComeFromStoreNotFromLockedList() throws IOException {
		SyncService sync = TestSyncServices.singleMode(appData);
		sync.startup(null, p -> {
		});
		Event event = new Event();
		event.setName("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		Presentation saturday = event.addPresentation("Samstag");
		Booking mueller = sold(friday, "Müller");
		Booking meier = sold(friday, "Meier");
		sold(friday, "Schmitt");
		Booking schulz = sold(saturday, "Schulz");
		TestRepositories.events(sync).saveEvent(event);
		ForeignVersions foreign = new ForeignVersions(sync, appData);
		UnitKey muellerUnit = unassigned(event, friday, mueller);
		foreign.write(muellerUnit, "Fremd", payload -> payload.put("paymentStatus", "x-test-voucher"));
		assertTrue(sync.unitState(muellerUnit).contentLocked());
		UnitKey meierUnit = unassigned(event, friday, meier);
		Version damaged = foreign.write(meierUnit, "Fremd", payload -> payload.put("lastName", "Meier-Neu"));
		Files.writeString(appData.resolve("data/" + meierUnit.relativePath() + "/" + damaged.id() + ".json"), "kaputt");
		assertEquals(UnitState.Status.LOCKED_DAMAGED, sync.unitState(meierUnit).status());
		UnitKey schulzUnit = unassigned(event, saturday, schulz);
		foreign.write(schulzUnit, "Fremd", payload -> payload.put("paymentStatus", "x-test-voucher"));

		// der Bestand, wie die Abrechnung ihn nach dem Laden zeigt
		StoreEventRepository repository = TestRepositories.events(sync);
		Event shown = repository.loadEvents().get(0);
		Presentation shownFriday = shown.findPresentation(friday.getId());
		assertNull(find(shownFriday, "Müller"), "Müller fehlt im Modell");
		assertNotNull(find(shownFriday, "Meier"), "Meier steht mit dem älteren Stand da");

		assertEquals(new Counts(1, 1), LockedBookingsNotice.count(repository, shown, shownFriday));
		assertEquals(new Counts(1, 0), LockedBookingsNotice.count(repository, shown, shown.findPresentation(saturday.getId())));
		assertEquals(new Counts(2, 1), LockedBookingsNotice.count(repository, shown, null), "Alle (Gesamtbilanz)");
		assertEquals(new Counts(0, 0), LockedBookingsNotice.count(repository, null, null), "ohne Event");
	}

	@Test
	void linkOpensLockedEntriesAndHidesWithoutText() throws Exception {
		AtomicInteger opened = new AtomicInteger();
		LockedSeats.install(Set::of, opened::incrementAndGet);

		LockedBookingsNotice notice = onFx(() -> {
			LockedBookingsNotice n = new LockedBookingsNotice();
			n.show(new Counts(1, 0));
			return n;
		});
		assertTrue(notice.isVisible());
		assertTrue(notice.isManaged());
		assertEquals("1 gesperrte Buchung ist nicht berücksichtigt", notice.getText());

		onFx(() -> {
			notice.fire();
			return null;
		});
		assertEquals(1, opened.get(), "öffnet \"Gesperrte Einträge\"");

		onFx(() -> {
			notice.show(new Counts(0, 0));
			return null;
		});
		assertFalse(notice.isVisible());
		assertFalse(notice.isManaged());
	}

	private static Booking sold(Presentation presentation, String lastName) {
		Booking booking = presentation.addBooking();
		booking.setUnassigned(true);
		booking.setLastName(lastName);
		return booking;
	}

	private static UnitKey unassigned(Event event, Presentation presentation, Booking booking) {
		return UnitKey.unassignedBooking(event.getId(), presentation.getId(), booking.getId());
	}

	private static Booking find(Presentation presentation, String lastName) {
		return presentation.getBookings().stream().filter(b -> lastName.equals(b.getLastName())).findFirst().orElse(null);
	}
}
