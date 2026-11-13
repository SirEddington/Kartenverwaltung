package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Shape;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.model.TextAlignH;
import de.eltviller_carneval_verein.karten.model.TextAlignV;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.repository.TestRepositories;

/**
 * Erzeugt einen Beispielordner für {@code src/test/resources/format-samples/<version>/} mit dem Code dieser Version, über
 * den echten {@link SyncService} im Einzelbetrieb und die Repositories. Der Inhalt ist derselbe wie im Beispiel von 1.0.1
 * (siehe dessen README), damit {@code FormatSamplesTest} jedes Beispiel gleich prüfen kann:
 * <ul>
 * <li>Halle „Stadthalle“ mit „Bühne“ (Rechteck, links/oben) und „Theke“ (Kreis, rechts/unten), eine Bestuhlung mit
 * Reihe A, zwei Tischen und je drei Sitzen;</li>
 * <li>Event „Prunksitzung“ mit der Vorstellung „Freitag“, je eine Buchung in {@code NONE} (Müller), {@code CASH} (Meier),
 * {@code CARD} (Schmitz) und {@code TRANSFER} (Becker);</li>
 * <li>„Schulz“ ({@code CASH}) auf einem vierten Sitz an Tisch 2, der danach im Plan gelöscht wird: Beim Öffnen gleicht
 * die App ab, und Schulz ist ohne Platz. Reihe/Tisch/Nummer (A/2/4) passen zu keinem Sitz.</li>
 * </ul>
 * Aufruf mit dem Zielordner, etwa {@code src/test/resources/format-samples/<version>}: Das Beispiel entsteht in einem
 * temporären Datenordner (nie im echten der App), sein Unterordner {@code data} kommt unverändert als {@code data.zip}
 * in den Zielordner. Ein Archiv statt der einzelnen Dateien, weil deren Pfade (Event, Vorstellung, Buchung, Ansprüche)
 * für Git unter Windows zu lang sind; zugleich bleiben die Bytes genau so, wie sie geschrieben wurden.
 */
public final class FormatSampleGenerator {

	/** Der Name der Halle. */
	public static final String HALL = "Stadthalle";
	/** Der Name des Events. */
	public static final String EVENT = "Prunksitzung";
	/** Der Name des Archivs im Beispielordner. */
	public static final String ARCHIVE = "data.zip";

	/** Die geschriebene Halle und das geschriebene Event, wie sie im Modell stehen. */
	public record Sample(Hall hall, Event event) {
	}

	private FormatSampleGenerator() {
	}

	/** @param args der Zielordner, in den {@value #ARCHIVE} kommt */
	public static void main(String[] args) throws IOException {
		if (args.length != 1) {
			System.err.println("Aufruf: FormatSampleGenerator <Zielordner>");
			System.exit(2);
		}
		Path archive = Path.of(args[0]).resolve(ARCHIVE);
		if (Files.exists(archive)) {
			throw new IllegalArgumentException("Das Archiv gibt es schon: " + archive);
		}
		Path appData = Files.createTempDirectory("format-sample");
		generate(appData);
		Files.createDirectories(archive.getParent());
		zipData(appData, archive);
		System.out.println("Beispiel geschrieben: " + archive + " (Datenordner " + appData + ")");
	}

	/**
	 * Packt den Unterordner {@code data} von {@code appData} unverändert in das Archiv {@code archive}; die Einträge
	 * heißen {@code data/…} mit {@code /} als Trenner, in sortierter Reihenfolge.
	 */
	public static void zipData(Path appData, Path archive) throws IOException {
		List<Path> files;
		try (Stream<Path> walk = Files.walk(appData.resolve("data"))) {
			files = walk.filter(Files::isRegularFile).sorted().toList();
		}
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW))) {
			for (Path file : files) {
				String name = StreamSupport.stream(appData.relativize(file).spliterator(), false).map(Path::toString)
						.collect(Collectors.joining("/"));
				zip.putNextEntry(new ZipEntry(name));
				Files.copy(file, zip);
				zip.closeEntry();
			}
		}
	}

	/**
	 * Schreibt das Beispiel im Einzelbetrieb in den Datenordner {@code appData} und beendet den Dienst sauber.
	 *
	 * @throws IllegalArgumentException wenn {@code appData} schon Dateien enthält
	 */
	public static Sample generate(Path appData) throws IOException {
		if (Files.isDirectory(appData)) {
			try (Stream<Path> list = Files.list(appData)) {
				if (list.findAny().isPresent()) {
					throw new IllegalArgumentException("Der Zielordner ist nicht leer: " + appData);
				}
			}
		}
		Files.createDirectories(appData);
		SyncService sync = TestSyncServices.singleMode(appData);
		sync.startup(null);
		Sample sample = fill(sync);
		sync.closeIndex();
		return sample;
	}

	/** Schreibt Halle und Event über die Repositories des gestarteten Dienstes, Schritt für Schritt wie in der App. */
	public static Sample fill(SyncService sync) {
		StoreHallRepository halls = TestRepositories.halls(sync);
		StoreEventRepository events = TestRepositories.events(sync);
		halls.loadHalls();
		events.loadEvents();

		Hall hall = new Hall();
		hall.setName(HALL);
		hall.setDescription("Beispiel");
		hall.setHallWidth(800);
		hall.setHallHeight(600);
		HallObject stage = hall.addHallObject("Bühne");
		stage.setShape(Shape.RECTANGLE);
		stage.setTextAlignH(TextAlignH.LEFT);
		stage.setTextAlignV(TextAlignV.TOP);
		stage.setPosX(100);
		stage.setPosY(20);
		stage.setWidth(400);
		stage.setHeight(80);
		stage.setColor("#c0c0c0");
		HallObject bar = hall.addHallObject("Theke");
		bar.setShape(Shape.CIRCLE);
		bar.setTextAlignH(TextAlignH.RIGHT);
		bar.setTextAlignV(TextAlignV.BOTTOM);
		bar.setPosX(650);
		bar.setPosY(450);
		bar.setWidth(90);
		bar.setHeight(90);
		bar.setDesc("Getränke");
		SeatingPlan plan = hall.addSeatingPlan();
		plan.setName("Reihenbestuhlung");
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, 2, 3);
		Table second = plan.getTablesOf(row).get(1);
		// ein vierter Sitz an Tisch 2, der später wieder gelöscht wird
		Seat extra = second.addSeat();
		halls.saveHall(hall);
		events.setHallLookup(id -> hall.getId().equals(id) ? hall : null);

		Event event = new Event();
		event.setName(EVENT);
		event.setDescription("Beispiel");
		Presentation friday = event.addPresentation("Freitag");
		friday.setDate(LocalDate.of(2027, 2, 5));
		friday.setTime(LocalTime.of(19, 11));
		friday.setPrice(2500);
		friday.setHallId(hall.getId());
		friday.setSeatingPlanId(plan.getId());
		BookingSync.sync(friday, plan, Set.of());
		book(friday, plan.getSeats().get(0), "Müller", PaymentStatus.NONE);
		book(friday, plan.getSeats().get(1), "Meier", PaymentStatus.CASH);
		book(friday, plan.getSeats().get(2), "Schmitz", PaymentStatus.CARD);
		book(friday, plan.getSeats().get(3), "Becker", PaymentStatus.TRANSFER);
		book(friday, extra, "Schulz", PaymentStatus.CASH);
		events.saveEvent(event);

		// der Sitz von Schulz wird im Plan gelöscht
		second.deleteSeat(extra);
		halls.saveHall(hall);
		// beim Öffnen des Events gleicht die App ab (BookingSyncService): Schulz ist danach ohne Platz
		events.deriving(event, () -> BookingSync.sync(friday, plan, Set.of()));
		Booking schulz = friday.getBookings().stream().filter(b -> "Schulz".equals(b.getLastName())).findFirst()
				.orElseThrow();
		if (!schulz.isUnassigned()) {
			throw new IllegalStateException("Schulz ist nicht ohne Platz");
		}
		events.saveEvent(event);
		return new Sample(hall, event);
	}

	private static void book(Presentation presentation, Seat seat, String lastName, PaymentStatus status) {
		Booking booking = presentation.getBookings().stream().filter(b -> seat.getId().equals(b.getSeatId()))
				.findFirst().orElseThrow();
		booking.setLastName(lastName);
		booking.setPaymentStatus(status);
		booking.setReservationDate(LocalDate.of(2026, 11, 2));
		if (status.isPaid()) {
			booking.setPaymentDate(LocalDate.of(2026, 11, 3));
		}
	}
}
