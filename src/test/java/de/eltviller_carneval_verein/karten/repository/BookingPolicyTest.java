package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ConflictInput.Sibling;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ConflictNote.Kind;
import de.eltviller_carneval_verein.karten.store.ConflictPolicy;
import de.eltviller_carneval_verein.karten.store.Resolution;
import de.eltviller_carneval_verein.karten.store.TrailCollector;
import de.eltviller_carneval_verein.karten.store.TrailUnit;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;
import de.eltviller_carneval_verein.karten.store.VersionMarks;

/** Die Strategie für Buchungen auf JSON, ohne Versionsspeicher. */
class BookingPolicyTest {

	private static final ObjectMapper MAPPER = JsonMapperFactory.createCompact();
	private static final String OWN = "ownmach1";
	private static final String OTHER = "othermac";
	private static final String THIRD = "thirdmac";

	private static final String EVENT = "event-1";
	private static final String PRESENTATION = "pres-1";
	private static final UnitKey S1 = seat("s1");
	private static final UnitKey S2 = seat("s2");
	private static final UnitKey S3 = seat("s3");

	private static final ConflictPolicy POLICY = new BookingPolicy();

	private static int counter;

	private static UnitKey seat(String seatId) {
		return UnitKey.booking(EVENT, PRESENTATION, seatId);
	}

	private static ObjectNode json(String text) {
		try {
			return (ObjectNode) MAPPER.readTree(text);
		} catch (IOException e) {
			throw new IllegalArgumentException(e);
		}
	}

	/**
	 * Eine Buchung so, wie das Repository sie schreibt: Kunde Max Muster auf Reihe A, Tisch 1, Sitz 1, danach die
	 * Änderungen aus {@code edit}.
	 */
	private static ObjectNode booking(String id, String seatId, Consumer<Booking> edit) {
		Booking booking = new Booking();
		booking.setId(id);
		booking.setSeatId(seatId);
		booking.setRowLabel("A");
		booking.setTableNumber(1);
		booking.setSeatNumber(1);
		booking.setCategory("Parkett");
		booking.setLastName("Muster");
		booking.setFirstName("Max");
		booking.setEMail("max@example.org");
		edit.accept(booking);
		return UnitMapper.normalize(booking);
	}

	private static ObjectNode booking(String id, String seatId) {
		return booking(id, seatId, b -> {
		});
	}

	private static Version version(String machine, String saveId, ObjectNode payload) {
		return new Version(1, VersionId.create(1_800_000_000_000L + counter, machine, counter++), List.of(), machine,
				"Kasse " + machine, "Bediener " + machine, Instant.EPOCH, saveId, false, payload);
	}

	private static Version version(String machine, ObjectNode payload) {
		return version(machine, "save-" + counter, payload);
	}

	private static Version deletion(String machine, String saveId) {
		return new Version(1, VersionId.create(1_800_000_000_000L + counter, machine, counter++), List.of(), machine,
				"Kasse " + machine, "Bediener " + machine, Instant.EPOCH, saveId, true, null);
	}

	private static Version deletion(String machine) {
		return deletion(machine, "save-" + counter);
	}

	private static ConflictInput input(UnitKey unit, Version base, Version mine, Version theirs, Version... chain) {
		return new ConflictInput(unit, base, mine, theirs, List.of(chain), OWN, Map.of());
	}

	private static ConflictInput input(UnitKey unit, Version base, Version mine, Version theirs,
			Map<UnitKey, Sibling> siblings, Version... chain) {
		return new ConflictInput(unit, base, mine, theirs, List.of(chain), OWN, siblings);
	}

	/** Eine Geschwister-Einheit, deren gültige Kette mit {@code version} endet und die nur diese Version hat. */
	private static Sibling sibling(UnitKey unit, Version version) {
		return new Sibling(new UnitSnapshot(unit, version.id(), Status.OK, version.deleted(), version.payload()));
	}

	@SafeVarargs
	private static Map<UnitKey, Sibling> siblings(Map.Entry<UnitKey, Version>... entries) {
		Map<UnitKey, Sibling> siblings = new LinkedHashMap<>();
		for (Map.Entry<UnitKey, Version> entry : entries) {
			siblings.put(entry.getKey(), sibling(entry.getKey(), entry.getValue()));
		}
		return siblings;
	}

	private static ConflictNote.Party party(Version version) {
		return new ConflictNote.Party(version.machine(), version.machineName(), version.operator(), version.id());
	}

	private static Change deletionOf(UnitKey unit, Version current) {
		return new Change(unit, List.of(current.id()), null, true);
	}

	// Regel 3: Doppelverkauf -->

	@Test
	void doubleSaleKeepsTheirsAndMovesMineWithoutPlace() {
		Version mine = version(OWN, booking("m", "s1", b -> b.setPaymentStatus(PaymentStatus.CASH)));
		Version theirs = version(OTHER, booking("t", "s1", b -> b.setLastName("Schulz")));
		ObjectNode mineBefore = mine.payload().deepCopy();

		Resolution resolution = POLICY.resolve(input(S1, null, mine, theirs, theirs));

		assertFalse(resolution.deleted());
		assertEquals(theirs.payload(), resolution.payload());
		UnitKey moved = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		assertEquals(1, resolution.extra().size());
		Change extra = resolution.extra().get(0);
		assertEquals(moved, extra.unit());
		assertTrue(extra.parents().isEmpty());
		assertFalse(extra.deleted());
		ObjectNode payload = extra.payload();
		assertEquals("Muster", payload.get("lastName").asText());
		assertEquals("Max", payload.get("firstName").asText());
		assertEquals(mine.payload().get("email"), payload.get("email"));
		assertEquals("max@example.org", payload.get("email").asText());
		assertEquals("CASH", payload.get("paymentStatus").asText());
		assertEquals("Parkett", payload.get("category").asText());
		assertTrue(payload.get("unassigned").asBoolean());
		assertTrue(payload.get("seatId").isNull());
		assertEquals("", payload.get("rowLabel").asText());
		assertEquals(0, payload.get("tableNumber").asInt());
		assertEquals(0, payload.get("seatNumber").asInt());
		// die Buchung "ohne Platz" liest das Zusammensetzen dort, wohin sie geschrieben wird
		assertTrue(UnitMapper.isReadable(new UnitSnapshot(moved, mine.id(), Status.OK, false, payload)));

		ConflictNote note = resolution.note();
		assertEquals(Kind.DOUBLE_SALE, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(moved, note.moved());
		assertEquals(json("{\"seatId\":\"s1\",\"rowLabel\":\"A\",\"tableNumber\":1,\"seatNumber\":1}"), note.place());
		assertEquals(mine.id(), note.dropped());
		assertEquals(party(theirs), note.other());
		assertTrue(note.fields().isEmpty());
		// die Eingaben bleiben unverändert
		assertEquals(mineBefore, mine.payload());
	}

	@Test
	void doubleSaleOtherIsTheVersionThatIntroducedTheirBooking() {
		Version base = version(THIRD, booking("x", "s1"));
		Version deleted = deletion(THIRD);
		Version sold = version(THIRD, booking("t", "s1"));
		// Deren ist eine spätere Änderung derselben Buchung t durch einen anderen Rechner
		Version theirs = version(OTHER, booking("t", "s1", b -> b.setComment("bezahlt bar")));
		Version mine = version(OWN, booking("m", "s1"));

		Resolution resolution = POLICY.resolve(input(S1, null, mine, theirs, base, deleted, sold, theirs));

		assertEquals(Kind.DOUBLE_SALE, resolution.note().kind());
		assertEquals(party(sold), resolution.note().other());
	}

	@Test
	void doubleSaleRevivesADeletedUnitWithoutPlace() {
		// Die Buchung m stand schon einmal ohne Platz und wurde von dort auf s1 zugewiesen (_m ist gelöscht)
		UnitKey moved = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		Version earlier = deletion(OWN);
		Version mine = version(OWN, booking("m", "s1"));
		Version theirs = version(OTHER, booking("t", "s1"));

		Resolution resolution = POLICY.resolve(input(S1, null, mine, theirs, siblings(Map.entry(moved, earlier)), theirs));

		// eine neue Erstversion übersprünge die Auflösung (die Einheit hat schon Versionen): neue Version auf der Löschung
		Change extra = resolution.extra().get(0);
		assertEquals(moved, extra.unit());
		assertEquals(List.of(earlier.id()), extra.parents());
		assertFalse(extra.deleted());
		assertTrue(extra.payload().get("unassigned").asBoolean());
		assertEquals(Kind.DOUBLE_SALE, resolution.note().kind());
	}

	@Test
	void cancelThenResellAgainstEditIsDeleteWins() {
		Version base = version(THIRD, booking("x", "s1"));
		Version cancelled = deletion(OTHER);
		Version resold = version(OTHER, booking("y", "s1", b -> b.setLastName("Neu")));
		Version mine = version(OWN, booking("x", "s1", b -> b.setPaymentStatus(PaymentStatus.CARD)));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, resold, cancelled, resold));

		assertFalse(resolution.deleted());
		assertEquals(resold.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty());
		ConflictNote note = resolution.note();
		assertEquals(Kind.DELETE_WINS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(party(cancelled), note.other());
		assertEquals(mine.id(), note.dropped());
	}

	@Test
	void cancelAndResellInOneSaveAgainstEditKeepsTheCancellation() {
		// Ergebnis A: Deren storniert x und verkauft y in einem Speichervorgang (s1: x → y, keine Löschversion);
		// Meine ändert x. x lebt nirgends mehr: Die Stornierung gilt, x wird nicht als _x wiederbelebt
		Version base = version(THIRD, booking("x", "s1"));
		Version theirs = version(OTHER, booking("y", "s1"));
		Version mine = version(OWN, booking("x", "s1", b -> b.setComment("Rollstuhl")));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertFalse(resolution.deleted());
		assertEquals(theirs.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty());
		ConflictNote note = resolution.note();
		assertEquals(Kind.DELETE_WINS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(party(theirs), note.other());
		assertEquals(mine.id(), note.dropped());
	}

	@Test
	void myReplacementKeepsTheSeatAndCancelsTheirEditedBooking() {
		// Spiegel zu Ergebnis A: Meine ersetzt x durch y (storniert x), Deren ändert x: y bleibt auf dem Sitz
		Version base = version(THIRD, booking("x", "s1"));
		Version mine = version(OWN, booking("y", "s1", b -> b.setLastName("Neu")));
		Version theirs = version(OTHER, booking("x", "s1", b -> b.setComment("Rollstuhl")));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertFalse(resolution.deleted());
		assertEquals(mine.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty());
		ConflictNote note = resolution.note();
		assertEquals(Kind.DELETE_WINS, note.kind());
		assertEquals(OTHER, note.affected());
		assertEquals(party(mine), note.other());
		assertEquals(theirs.id(), note.dropped());
	}

	/** Die Buchung m "ohne Platz", wie das Repository sie auf {@code _m} schreibt, danach die Änderungen aus {@code edit}. */
	private static ObjectNode withoutPlace(String id, Consumer<Booking> edit) {
		return booking(id, null, b -> {
			b.setRowLabel("");
			b.setTableNumber(0);
			b.setSeatNumber(0);
			b.setUnassigned(true);
			edit.accept(b);
		});
	}

	// R35: Zuweisen aus "ohne Platz" gegen eine gleichzeitige Änderung, als Matrix -->

	/** Was A mit m macht, während es s1 an t verkauft. */
	enum ForeignAction {
		/** m auf {@code _m} ändern (bezahlt). */
		EDIT,
		/** m stornieren ({@code _m} löschen). */
		CANCEL,
		/** m von {@code _m} nach s4 setzen. */
		MOVE_TO_S4,
		/** m nach s4 setzen und in einem zweiten Speichervorgang weiter nach s5. */
		MOVE_TO_S4_THEN_S5
	}

	/** Wer welche Einheit gewinnt, und wer in welcher Reihenfolge auflöst. */
	enum Course {
		/** A gewinnt beide Einheiten; B löst erst s1, dann {@code _m} auf. */
		A_WINS_BOTH_SEAT_FIRST(false),
		/** A gewinnt beide Einheiten; B löst erst {@code _m}, dann s1 auf. */
		A_WINS_BOTH_UNASSIGNED_FIRST(false),
		/** B gewinnt {@code _m}, A gewinnt s1; B löst s1 auf, bevor A {@code _m} auflöst (und A sieht B's Ergebnis). */
		SPLIT_SEAT_BEFORE_FOREIGN_MERGE(true),
		/** B gewinnt {@code _m}, A gewinnt s1; A löst {@code _m} auf, danach B s1 (und B sieht A's Zusammenführung). */
		SPLIT_SEAT_AFTER_FOREIGN_MERGE(true);

		final boolean split;

		Course(boolean split) {
			this.split = split;
		}
	}

	/**
	 * Ein Versionsspeicher im Kleinen: je Einheit alle Versionen ihres Ordners und die gültige Kette, je Einheit und
	 * Rechner die verlorene Strecke. {@link #resolve} bildet die Eingabe wie {@code ConflictResolver} (Basis, Meine, Deren,
	 * Kette seit der Basis, Speichervorgänge der Strecke, Geschwister mit Speichervorgängen, verlorenem Umzugsziel und
	 * Löschungen) und schreibt die Auflösung (weitere Änderungen, dann die Zusammenführung) in die Ketten. Gabelungen
	 * zwischen Auflösungen gibt es hier nicht: Wer auflöst, sieht alles, was vorher aufgelöst wurde.
	 */
	private static final class World {
		private final Map<UnitKey, List<Version>> chains = new LinkedHashMap<>();
		private final Map<UnitKey, List<Version>> folders = new LinkedHashMap<>();
		private final Map<UnitKey, Map<String, List<Version>>> lost = new LinkedHashMap<>();
		/** Die geschriebenen Vermerke. */
		final List<ConflictNote> notes = new ArrayList<>();
		private int merges;

		void claim(UnitKey unit, Version version) {
			List<Version> chain = chains.computeIfAbsent(unit, key -> new ArrayList<>());
			assertEquals(chain.isEmpty() ? null : chain.get(chain.size() - 1).id(), version.firstParent(),
					"keine Gabelung an " + unit.relativePath());
			chain.add(version);
			folders.computeIfAbsent(unit, key -> new ArrayList<>()).add(version);
		}

		void lose(UnitKey unit, Version version) {
			folders.computeIfAbsent(unit, key -> new ArrayList<>()).add(version);
			lost.computeIfAbsent(unit, key -> new LinkedHashMap<>())
					.computeIfAbsent(version.machine(), key -> new ArrayList<>()).add(version);
		}

		Version head(UnitKey unit) {
			List<Version> chain = chains.getOrDefault(unit, List.of());
			return chain.isEmpty() ? null : chain.get(chain.size() - 1);
		}

		/** Die Einheiten, deren Stand die Buchung trägt. */
		List<UnitKey> carriers(String bookingId) {
			return chains.keySet().stream().filter(unit -> bookingId.equals(idOf(head(unit)))).toList();
		}

		/** Ob kein Rechner mehr eine verlorene Strecke hat. */
		boolean settled() {
			return lost.values().stream().allMatch(Map::isEmpty);
		}

		/** Die Vermerke, die einen Rechner betreffen. */
		List<ConflictNote> notesFor(String machine) {
			return notes.stream().filter(note -> machine.equals(note.affected())).toList();
		}

		/** Löst die verlorene Strecke des Rechners an der Einheit auf, wie der Resolver auf diesem Rechner. */
		void resolve(UnitKey unit, String machine) {
			List<Version> stretch = lost.get(unit).remove(machine);
			ConflictInput input = input(unit, machine, stretch);
			Version mine = input.mine();
			Version theirs = input.theirs();
			Resolution resolution = POLICY.resolve(input);
			String saveId = "merge-" + ++merges;
			for (Change extra : resolution.extra()) {
				Version current = head(extra.unit());
				boolean there = extra.deleted() ? current == null || current.deleted()
						: extra.parents().isEmpty() && current != null;
				if (!there) {
					write(extra.unit(), extra.parents(), machine, saveId, extra.deleted(), extra.payload(), extra.note());
				}
			}
			write(unit, List.of(theirs.id(), mine.id()), machine, saveId, resolution.deleted(), resolution.payload(),
					resolution.note());
		}

		/**
		 * Die Eingabe für die verlorene Strecke des Rechners an der Einheit, wie der Resolver auf diesem Rechner sie bildet:
		 * Basis, Meine, Deren, Kette seit der Basis, Speichervorgänge und Geschwister, Strecke und Umzugsspur
		 * ({@link TrailCollector} über die Ordner dieser Welt).
		 */
		ConflictInput input(UnitKey unit, String machine, List<Version> stretch) {
			Version mine = stretch.get(stretch.size() - 1);
			VersionId baseId = stretch.get(0).firstParent();
			List<Version> chain = chains.get(unit);
			int at = -1;
			for (int i = 0; baseId != null && i < chain.size(); i++) {
				if (chain.get(i).id().equals(baseId)) {
					at = i;
				}
			}
			Version base = at < 0 ? null : chain.get(at);
			Version theirs = head(unit);
			List<Version> since = chain.subList(at + 1, chain.size()).stream().map(BookingPolicyTest::copy).toList();
			Map<UnitKey, TrailUnit> trail;
			try {
				trail = TrailCollector.collect(unit, base, stretch, key -> {
					Version head = head(key);
					return new TrailCollector.Folder(chains.getOrDefault(key, List.of()),
							folders.getOrDefault(key, List.of()), head == null ? null : head.id());
				});
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return new ConflictInput(unit, copy(base), copy(mine), copy(theirs), since, machine,
					siblings(unit), stretch.stream().map(BookingPolicyTest::copy).toList(), trail);
		}

		private void write(UnitKey unit, List<VersionId> parents, String machine, String saveId, boolean deleted,
				ObjectNode payload, ConflictNote note) {
			claim(unit, new Version(1, VersionId.create(1_800_000_000_000L + counter, machine, counter++), parents, machine,
					"Kasse " + machine, "Bediener " + machine, Instant.EPOCH, saveId, deleted, deleted ? null : payload,
					note, Map.of(), VersionMarks.RESOLUTION));
			if (note != null) {
				notes.add(note);
			}
		}

		/** Die Geschwister wie {@code ConflictResolver.siblings}: Stand und Kettenende jeder anderen Einheit. */
		private Map<UnitKey, Sibling> siblings(UnitKey unit) {
			Map<UnitKey, Sibling> siblings = new LinkedHashMap<>();
			for (UnitKey other : chains.keySet()) {
				Version head = head(other);
				if (!other.equals(unit)) {
					siblings.put(other, new Sibling(new UnitSnapshot(other, head.id(), Status.OK, head.deleted(),
							head.deleted() ? null : head.payload().deepCopy())));
				}
			}
			return siblings;
		}
	}

	/** Eine Kopie der Version, wie der Resolver sie der Strategie gibt. */
	private static Version copy(Version version) {
		return version == null ? null
				: new Version(version.format(), version.id(), version.parents(), version.machine(), version.machineName(),
						version.operator(), version.at(), version.saveId(), version.deleted(),
						version.payload() == null ? null : version.payload().deepCopy(), version.conflict(), version.seen(),
						version.marks());
	}

	/** Die Buchungs-ID einer Version mit Inhalt; {@code null} ohne Version oder bei einer Löschung. */
	private static String idOf(Version version) {
		return version == null || version.deleted() ? null : version.payload().path("id").asText(null);
	}

	/** Eine Version auf einem Vorgänger; ohne Inhalt eine Löschung. */
	private static Version child(String machine, String saveId, Version parent, ObjectNode payload) {
		return child(machine, saveId, parent, payload, VersionMarks.NONE);
	}

	/** Eine Version auf einem Vorgänger mit Angaben zum Umzug; ohne Inhalt eine Löschung. */
	private static Version child(String machine, String saveId, Version parent, ObjectNode payload, VersionMarks marks) {
		return new Version(1, VersionId.create(1_800_000_000_000L + counter, machine, counter++), List.of(parent.id()),
				machine, "Kasse " + machine, "Bediener " + machine, Instant.EPOCH, saveId, payload == null, payload, null,
				Map.of(), marks);
	}

	/** Eine erste Version mit Angaben zum Umzug. */
	private static Version version(String machine, String saveId, ObjectNode payload, VersionMarks marks) {
		return new Version(1, VersionId.create(1_800_000_000_000L + counter, machine, counter++), List.of(), machine,
				"Kasse " + machine, "Bediener " + machine, Instant.EPOCH, saveId, false, payload, null, Map.of(), marks);
	}

	/**
	 * Der Fall aus R35: m steht "ohne Platz" ({@code _m}). B (dieser Rechner, OWN) weist m dem Sitz s1 zu und ändert dabei die
	 * E-Mail (save-a: Löschung von {@code _m}, m auf s1), bei zwei Speichervorgängen danach noch den Kommentar an m auf s1
	 * (save-c). A (OTHER) verkauft s1 an t und ändert, storniert oder verschiebt m im selben Speichervorgang (save-b);
	 * weitergeschoben nach s5 wird in save-d. A gewinnt s1, {@code _m} gewinnt je nach Verlauf A oder B; danach lösen die
	 * Verlierer in der Reihenfolge des Verlaufs auf.
	 */
	private static World assignmentAgainst(ForeignAction action, int ownSaves, Course course) {
		return assignmentAgainst(action, ownSaves, course, Source.UNASSIGNED, false);
	}

	/** Wo m vor B's Umzug steht. */
	enum Source {
		/** "ohne Platz" ({@code _m}): B weist einen Platz zu. */
		UNASSIGNED,
		/** auf s3: B setzt m um (R34 (2), "Sitz als Quelle"). */
		SEAT;

		UnitKey unit() {
			return this == UNASSIGNED ? UnitKey.unassignedBooking(EVENT, PRESENTATION, "m") : S3;
		}

		/** m an der Quelle, danach die Änderungen aus {@code edit}. */
		ObjectNode booking(Consumer<Booking> edit) {
			return this == UNASSIGNED ? withoutPlace("m", edit) : BookingPolicyTest.booking("m", "s3", edit);
		}
	}

	/**
	 * Wie {@link #assignmentAgainst(ForeignAction, int, Course)}, mit m an der Quelle {@code source} (in {@link Course}
	 * steht {@code _m} für die Quelle); mit {@code editFirst} ändert B m dort zuerst in einem eigenen Speichervorgang
	 * (Kommentar "vorher") und setzt es erst danach um.
	 */
	private static World assignmentAgainst(ForeignAction action, int ownSaves, Course course, Source source,
			boolean editFirst) {
		World world = new World();
		UnitKey unassigned = source.unit();
		UnitKey s4 = seat("s4");
		Version base = version(THIRD, "save-0", source.booking(b -> {
		}));
		world.claim(unassigned, base);
		List<Version> mySource = new ArrayList<>();
		if (editFirst) {
			mySource.add(child(OWN, "save-e", base, source.booking(b -> b.setComment("vorher"))));
		}
		mySource.add(child(OWN, "save-a", editFirst ? mySource.get(0) : base, null, VersionMarks.NONE.withMovedTo(S1)));
		List<Version> mySeat = new ArrayList<>(List.of(version(OWN, "save-a", booking("m", "s1", b -> {
			b.setEMail("neu@example.org");
			if (editFirst) {
				b.setComment("vorher");
			}
		}), VersionMarks.NONE.withMovedFrom(unassigned))));
		if (ownSaves == 2) {
			mySeat.add(child(OWN, "save-c", mySeat.get(0), booking("m", "s1", b -> {
				b.setEMail("neu@example.org");
				b.setComment("Fenster");
			})));
		}
		Version theirSeat = version(OTHER, "save-b", booking("t", "s1", b -> b.setLastName("Tom")));
		boolean moves = action == ForeignAction.MOVE_TO_S4 || action == ForeignAction.MOVE_TO_S4_THEN_S5;
		Version theirM = switch (action) {
		case EDIT -> child(OTHER, "save-b", base, source.booking(b -> b.setPaymentStatus(PaymentStatus.CARD)));
		case CANCEL -> child(OTHER, "save-b", base, null);
		case MOVE_TO_S4, MOVE_TO_S4_THEN_S5 -> child(OTHER, "save-b", base, null, VersionMarks.NONE.withMovedTo(s4));
		};
		world.claim(S1, theirSeat);
		mySeat.forEach(version -> world.lose(S1, version));
		if (moves) {
			Version theirS4 = version(OTHER, "save-b", booking("m", "s4", b -> b.setSeatNumber(4)),
					VersionMarks.NONE.withMovedFrom(unassigned));
			world.claim(s4, theirS4);
			if (action == ForeignAction.MOVE_TO_S4_THEN_S5) {
				world.claim(s4, child(OTHER, "save-d", theirS4, null, VersionMarks.NONE.withMovedTo(seat("s5"))));
				world.claim(seat("s5"), version(OTHER, "save-d", booking("m", "s5", b -> b.setSeatNumber(5)),
						VersionMarks.NONE.withMovedFrom(s4)));
			}
		}
		if (course.split) {
			mySource.forEach(version -> world.claim(unassigned, version));
			world.lose(unassigned, theirM);
		} else {
			world.claim(unassigned, theirM);
			mySource.forEach(version -> world.lose(unassigned, version));
		}
		switch (course) {
		case A_WINS_BOTH_SEAT_FIRST -> {
			world.resolve(S1, OWN);
			world.resolve(unassigned, OWN);
		}
		case A_WINS_BOTH_UNASSIGNED_FIRST -> {
			world.resolve(unassigned, OWN);
			world.resolve(S1, OWN);
		}
		case SPLIT_SEAT_BEFORE_FOREIGN_MERGE -> {
			world.resolve(S1, OWN);
			world.resolve(unassigned, OTHER);
		}
		case SPLIT_SEAT_AFTER_FOREIGN_MERGE -> {
			world.resolve(unassigned, OTHER);
			world.resolve(S1, OWN);
		}
		}
		assertTrue(world.settled());
		return world;
	}

	/**
	 * Die Zellen, die die Matrix nicht halten kann: A verschiebt m nach s4, die Gewinner sind getrennt, und A löst
	 * {@code _m} auf, bevor B's Auflösung von s1 bei ihm ist.
	 */
	private static boolean heldOpen(ForeignAction action, Course course) {
		return action == ForeignAction.MOVE_TO_S4 && course == Course.SPLIT_SEAT_AFTER_FOREIGN_MERGE;
	}

	static Stream<Arguments> assignmentCells() {
		return cells(cell -> !heldOpen(cell.action(), cell.course()));
	}

	static Stream<Arguments> heldOpenCells() {
		return cells(cell -> heldOpen(cell.action(), cell.course()));
	}

	private record Cell(ForeignAction action, int ownSaves, Course course) {
	}

	private static Stream<Arguments> cells(Predicate<Cell> filter) {
		List<Arguments> cells = new ArrayList<>();
		for (ForeignAction action : ForeignAction.values()) {
			for (int ownSaves = 1; ownSaves <= 2; ownSaves++) {
				for (Course course : Course.values()) {
					if (filter.test(new Cell(action, ownSaves, course))) {
						cells.add(Arguments.of(action, ownSaves, course));
					}
				}
			}
		}
		return cells.stream();
	}

	/**
	 * R35: In jeder Variante und jeder Reihenfolge lebt m höchstens einmal, und nirgends genau dann, wenn A storniert hat;
	 * A's Umzug gilt, eine Änderung von A weicht nie still; B weicht mit genau einem Vermerk (Änderung: Doppelverkauf,
	 * Storno: DELETE_WINS, Umzug: FIELDS "Platz"), A hat nur dann einen, wenn seine Änderung weicht; s1 trägt t.
	 */
	@ParameterizedTest(name = "{0}, B speichert {1}x, {2}")
	@MethodSource("assignmentCells")
	void assignmentFromUnassignedAgainstConcurrentChangeKeepsInvariants(ForeignAction action, int ownSaves,
			Course course) {
		assertInvariants(assignmentAgainst(action, ownSaves, course), action);
	}

	/**
	 * Dieselben Invarianten für die Zellen aus Ruling R36 (getrennte Gewinner, A löst {@code _m} auf, bevor B's Auflösung
	 * von s1 bei ihm ist): Mit {@code movedTo} sieht A B's Löschung als Umzug, dessen Ziel verloren hat (KARV-93).
	 */
	@ParameterizedTest(name = "{0}, B speichert {1}x, {2}")
	@MethodSource("heldOpenCells")
	void foreignMoveAgainstAssignmentWithSplitWinnersKeepsInvariants(ForeignAction action, int ownSaves,
			Course course) {
		assertInvariants(assignmentAgainst(action, ownSaves, course), action);
	}

	/** Setzen beide Rechner dieselbe Buchung gleich um (H3), gibt es keinen Vermerk, und sie lebt einmal am Ziel. */
	@Test
	void sameMoveOnBothMachinesLeavesNoNote() {
		for (boolean seatFirst : new boolean[] { true, false }) {
			World world = new World();
			Version base = version(THIRD, "save-0", booking("m", "s1"));
			world.claim(S1, base);
			world.claim(S1, child(OTHER, "save-a", base, null, VersionMarks.NONE.withMovedTo(S2)));
			world.lose(S1, child(OWN, "save-b", base, null, VersionMarks.NONE.withMovedTo(S2)));
			world.claim(S2, version(OTHER, "save-a", booking("m", "s2"), VersionMarks.NONE.withMovedFrom(S1)));
			world.lose(S2, version(OWN, "save-b", booking("m", "s2"), VersionMarks.NONE.withMovedFrom(S1)));
			if (seatFirst) {
				world.resolve(S2, OWN);
				world.resolve(S1, OWN);
			} else {
				world.resolve(S1, OWN);
				world.resolve(S2, OWN);
			}

			assertEquals(List.of(S2), world.carriers("m"));
			assertEquals(List.of(), world.notes, "keine Vermerke");
		}
	}

	static Stream<Arguments> allCells() {
		return cells(cell -> true);
	}

	/** R34 (2), Sitz als Quelle: B setzt m von s3 nach s1 und verliert s1 an t; A ändert, storniert oder verschiebt m. */
	@ParameterizedTest(name = "{0}, B speichert {1}x, {2}")
	@MethodSource("allCells")
	void seatAsSourceKeepsInvariants(ForeignAction action, int ownSaves, Course course) {
		assertInvariants(assignmentAgainst(action, ownSaves, course, Source.SEAT, false), action, Source.SEAT);
	}

	/** B ändert m zuerst auf {@code _m} und weist es im nächsten Speichervorgang zu: Die ganze Strecke zählt. */
	@ParameterizedTest(name = "{0}, B speichert {1}x, {2}")
	@MethodSource("allCells")
	void severalSavesBeforeAssignmentKeepInvariants(ForeignAction action, int ownSaves, Course course) {
		World world = assignmentAgainst(action, ownSaves, course, Source.UNASSIGNED, true);
		assertInvariants(world, action);
		if (action != ForeignAction.CANCEL && ownSaves == 1) {
			assertEquals("vorher", world.head(world.carriers("m").get(0)).payload().path("comment").asText(),
					"B's erste Änderung erreicht den Träger");
		}
	}

	/** Ersetzen: B setzt y auf s1 und m nach s2; A ändert m auf s1. Die Änderung geht nach s2 (E5), s1 trägt y. */
	@ParameterizedTest(name = "B gewinnt s1: {0}")
	@ValueSource(booleans = { false, true })
	void replacementAgainstEditTransfersTheEdit(boolean ownWinsSeat) {
		World world = new World();
		Version m = version(THIRD, "save-0", booking("m", "s1"));
		world.claim(S1, m);
		Version replaced = child(OWN, "save-r", m, booking("y", "s1"), VersionMarks.NONE.withMovedTo(S2));
		Version edited = child(OTHER, "save-e", m, booking("m", "s1", b -> b.setPaymentStatus(PaymentStatus.CARD)));
		world.claim(S2, version(OWN, "save-r", booking("m", "s2"), VersionMarks.NONE.withMovedFrom(S1)));
		world.claim(S1, ownWinsSeat ? replaced : edited);
		world.lose(S1, ownWinsSeat ? edited : replaced);

		world.resolve(S1, ownWinsSeat ? OTHER : OWN);

		assertEquals(List.of(S2), world.carriers("m"));
		assertEquals("CARD", world.head(S2).payload().path("paymentStatus").asText());
		assertEquals("y", idOf(world.head(S1)));
		assertEquals(List.of(), world.notes);
	}

	/**
	 * Wie beim Ersetzen, aber B ändert y danach noch in einem zweiten Speichervorgang: Die Umzugsangaben stehen nur an der
	 * ersten Version der Strecke, A's Änderung geht trotzdem nach s2.
	 */
	@Test
	void replacementFollowedByAnEditOfTheNewSaleStillTransfers() {
		World world = new World();
		Version m = version(THIRD, "save-0", booking("m", "s1"));
		world.claim(S1, m);
		Version replaced = child(OWN, "save-a", m, booking("y", "s1"), VersionMarks.NONE.withMovedTo(S2));
		Version editedY = child(OWN, "save-b", replaced, booking("y", "s1", b -> b.setComment("geändert")));
		world.claim(S2, version(OWN, "save-a", booking("m", "s2"), VersionMarks.NONE.withMovedFrom(S1)));
		world.claim(S1, child(OTHER, "save-e", m, booking("m", "s1", b -> b.setPaymentStatus(PaymentStatus.CARD))));
		world.lose(S1, replaced);
		world.lose(S1, editedY);

		world.resolve(S1, OWN);

		assertEquals("geändert", world.head(S1).payload().path("comment").asText());
		assertEquals(List.of(S2), world.carriers("m"));
		assertEquals("CARD", world.head(S2).payload().path("paymentStatus").asText());
		assertEquals(List.of(), world.notes);
	}

	/**
	 * B setzt m von s3 nach s1 und verliert s1 an t; danach verkauft ein dritter Rechner z auf dem geräumten s3. A hatte m
	 * auf s3 geändert. z ist kein Storno von m, und s3 ist nicht mehr frei: m geht mit A's Änderung "ohne Platz", z bleibt.
	 */
	@ParameterizedTest(name = "erst s3: {0}")
	@ValueSource(booleans = { false, true })
	void saleOnTheVacatedSourceIsNoCancellationAndStays(boolean sourceFirst) {
		World world = new World();
		Version m = version(THIRD, "save-0", booking("m", "s3"));
		world.claim(S3, m);
		Version moved = child(OWN, "save-a", m, null, VersionMarks.NONE.withMovedTo(S1));
		world.claim(S3, moved);
		world.claim(S3, child(THIRD, "save-z", moved, booking("z", "s3")));
		world.lose(S3, child(OTHER, "save-e", m, booking("m", "s3", b -> b.setPaymentStatus(PaymentStatus.CARD))));
		world.claim(S1, version(OTHER, "save-t", booking("t", "s1")));
		world.lose(S1, version(OWN, "save-a", booking("m", "s1"), VersionMarks.NONE.withMovedFrom(S3)));

		if (sourceFirst) {
			world.resolve(S3, OTHER);
			world.resolve(S1, OWN);
		} else {
			world.resolve(S1, OWN);
			world.resolve(S3, OTHER);
		}

		assertEquals("z", idOf(world.head(S3)), "der Verkauf auf s3 bleibt");
		UnitKey unassigned = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		assertEquals(List.of(unassigned), world.carriers("m"));
		assertEquals("CARD", world.head(unassigned).payload().path("paymentStatus").asText());
		assertEquals(List.of(), world.notesFor(OTHER), "A's Änderung weicht nicht");
		assertEquals(1, world.notesFor(OWN).size(), world.notes.toString());
	}

	/** Tausch: B tauscht m (s1) und n (s2); A ändert m auf s1. Danach n auf s1, m mit A's Änderung auf s2. */
	@ParameterizedTest(name = "B gewinnt s1: {0}")
	@ValueSource(booleans = { false, true })
	void swapAgainstEditKeepsBothBookingsOnce(boolean ownWinsSeat) {
		World world = new World();
		Version m = version(THIRD, "save-0", booking("m", "s1"));
		Version n = version(THIRD, "save-0", booking("n", "s2"));
		world.claim(S1, m);
		world.claim(S2, n);
		Version nOnS1 = child(OWN, "save-t", m, booking("n", "s1"), new VersionMarks(S2, S2, false));
		Version edited = child(OTHER, "save-e", m, booking("m", "s1", b -> b.setPaymentStatus(PaymentStatus.CARD)));
		world.claim(S2, child(OWN, "save-t", n, booking("m", "s2"), new VersionMarks(S1, S1, false)));
		world.claim(S1, ownWinsSeat ? nOnS1 : edited);
		world.lose(S1, ownWinsSeat ? edited : nOnS1);

		world.resolve(S1, ownWinsSeat ? OTHER : OWN);

		assertEquals(List.of(S1), world.carriers("n"));
		assertEquals(List.of(S2), world.carriers("m"));
		assertEquals("CARD", world.head(S2).payload().path("paymentStatus").asText());
		assertEquals(List.of(), world.notes);
	}

	/**
	 * Beide setzen y von s9 auf s1 und ziehen m an verschiedene Ziele (A nach s2, B nach s3): s1 ist inhaltlich gleich,
	 * trotzdem lebt m danach einmal, am Ziel des Gewinners von s1 (E7 vor der Regel "Gleich").
	 */
	@ParameterizedTest(name = "B gewinnt s1: {0}")
	@ValueSource(booleans = { false, true })
	void differentTargetsWithEqualNewContentLeaveOneCarrier(boolean ownWinsSeat) {
		World world = new World();
		UnitKey s9 = seat("s9");
		Version y = version(THIRD, "save-0", booking("y", "s9"));
		Version m = version(THIRD, "save-0", booking("m", "s1"));
		world.claim(s9, y);
		world.claim(S1, m);
		Version theirsAtS1 = child(OTHER, "save-a", m, booking("y", "s1"), new VersionMarks(S2, s9, false));
		Version mineAtS1 = child(OWN, "save-b", m, booking("y", "s1"), new VersionMarks(S3, s9, false));
		world.claim(s9, child(OTHER, "save-a", y, null, VersionMarks.NONE.withMovedTo(S1)));
		world.lose(s9, child(OWN, "save-b", y, null, VersionMarks.NONE.withMovedTo(S1)));
		world.claim(S2, version(OTHER, "save-a", booking("m", "s2"), VersionMarks.NONE.withMovedFrom(S1)));
		world.claim(S3, version(OWN, "save-b", booking("m", "s3"), VersionMarks.NONE.withMovedFrom(S1)));
		world.claim(S1, ownWinsSeat ? mineAtS1 : theirsAtS1);
		world.lose(S1, ownWinsSeat ? theirsAtS1 : mineAtS1);

		world.resolve(S1, ownWinsSeat ? OTHER : OWN);
		world.resolve(s9, OWN);

		assertEquals(List.of(ownWinsSeat ? S3 : S2), world.carriers("m"));
		assertEquals(List.of(S1), world.carriers("y"));
		String loser = ownWinsSeat ? OTHER : OWN;
		assertEquals(1, world.notesFor(loser).size(), world.notes.toString());
		assertEquals(List.of(), world.notesFor(ownWinsSeat ? OWN : OTHER));
	}

	/** Eine zweite Auflösung mit derselben Strecke findet ihr Ergebnis vor und schreibt nichts noch einmal. */
	@Test
	void repeatedResolutionWritesNothingTwice() {
		World world = assignmentAgainst(ForeignAction.EDIT, 1, Course.SPLIT_SEAT_BEFORE_FOREIGN_MERGE);
		Version mine = world.folders.get(S1).stream().filter(v -> OWN.equals(v.machine()) && !v.marks().resolution())
				.findFirst().orElseThrow();

		Resolution again = POLICY.resolve(world.input(S1, OWN, List.of(mine)));

		assertEquals(List.of(), again.extra());
	}

	private static void assertInvariants(World world, ForeignAction action) {
		assertInvariants(world, action, Source.UNASSIGNED);
	}

	/**
	 * R35 mit KARV-93: wie oben; dazu bleibt A's Änderung immer (E9: m bleibt an der Quelle, B's Umzug ist gescheitert), und
	 * B weicht bei einer Änderung von A auf einem Sitz mit dem Vermerk "Platz" statt dem Doppelverkauf.
	 */
	private static void assertInvariants(World world, ForeignAction action, Source source) {
		List<UnitKey> carriers = world.carriers("m");
		assertTrue(carriers.size() <= 1, "1: m lebt höchstens einmal: " + carriers);
		assertEquals(action == ForeignAction.CANCEL ? 0 : 1, carriers.size(),
				"2: m lebt genau dann nirgends, wenn A storniert hat: " + carriers);
		boolean theirChangeYields = false;
		switch (action) {
		case MOVE_TO_S4 -> assertEquals(List.of(seat("s4")), carriers, "3: A's Umzug gilt");
		case MOVE_TO_S4_THEN_S5 -> assertEquals(List.of(seat("s5")), carriers, "3: A's Umzug gilt");
		case EDIT -> {
			assertEquals(List.of(source.unit()), carriers, "3: m bleibt an der Quelle");
			assertEquals("CARD", world.head(carriers.get(0)).payload().path("paymentStatus").asText(),
					"3: A's Änderung bleibt");
		}
		case CANCEL -> {
		}
		}
		if (action != ForeignAction.CANCEL) {
			assertEquals("neu@example.org", world.head(carriers.get(0)).payload().path("email").asText(),
					"R34 (1): B's E-Mail erreicht den Träger");
		}
		List<ConflictNote> mine = world.notesFor(OWN);
		assertEquals(1, mine.size(), "4: B weicht mit genau einem Vermerk: " + mine);
		Kind expected = switch (action) {
		case EDIT -> source == Source.UNASSIGNED ? Kind.DOUBLE_SALE : Kind.FIELDS;
		case CANCEL -> Kind.DELETE_WINS;
		case MOVE_TO_S4, MOVE_TO_S4_THEN_S5 -> Kind.FIELDS;
		};
		assertEquals(expected, mine.get(0).kind(), "4: Art von B's Vermerk");
		if (expected == Kind.FIELDS) {
			assertEquals(List.of("place"), mine.get(0).fields().stream().map(ConflictNote.FieldConflict::group).toList());
		}
		List<ConflictNote> theirs = world.notesFor(OTHER);
		assertEquals(theirChangeYields ? 1 : 0, theirs.size(),
				"3/4: A hat genau dann einen Vermerk, wenn seine Änderung weicht: " + theirs);
		if (theirChangeYields) {
			assertEquals(Kind.DELETE_WINS, theirs.get(0).kind());
		}
		assertEquals("t", idOf(world.head(S1)), "5: s1 trägt t");
	}

	@Test
	void bothReplacedWithDifferentBookingsIsADoubleSale() {
		// keine Seite trägt mehr x: zwei neue Verkäufe auf demselben Sitz
		Version base = version(THIRD, booking("x", "s1"));
		Version mine = version(OWN, booking("m", "s1"));
		Version theirs = version(OTHER, booking("t", "s1"));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertEquals(Kind.DOUBLE_SALE, resolution.note().kind());
		assertEquals(UnitKey.unassignedBooking(EVENT, PRESENTATION, "m"), resolution.extra().get(0).unit());
	}

	@Test
	void doubleSaleWithAnAliveUnassignedCopyCreatesNoSecondUnit() {
		// die Buchung m lebt schon ohne Platz: keine zweite lebende Einheit mit derselben Buchungs-ID
		UnitKey moved = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		Version alive = version(OTHER, booking("m", null, b -> {
			b.setRowLabel("");
			b.setTableNumber(0);
			b.setSeatNumber(0);
			b.setUnassigned(true);
		}));
		Version mine = version(OWN, booking("m", "s1"));
		Version theirs = version(OTHER, booking("t", "s1"));

		Resolution resolution = POLICY.resolve(input(S1, null, mine, theirs, siblings(Map.entry(moved, alive)), theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty());
		assertEquals(Kind.DOUBLE_SALE, resolution.note().kind());
		assertEquals(moved, resolution.note().moved());
		assertEquals(mine.id(), resolution.note().dropped());
	}

	@Test
	void unchangedMineTakesTheirsWithoutMovingIt() {
		Version base = version(THIRD, booking("x", "s1"));
		Version mine = version(OWN, booking("x", "s1"));
		Version theirs = version(OTHER, booking("y", "s1"));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertNull(resolution.note());
		assertTrue(resolution.extra().isEmpty());
	}

	@Test
	void unreadableTheirsIsTreatedAsDoubleSale() {
		Version mine = version(OWN, booking("m", "s1"));
		// Deren lässt sich nicht lesen: Der Zahlstatus ist keiner; die ID ist sogar dieselbe
		ObjectNode broken = booking("m", "s1");
		broken.put("paymentStatus", "KAPUTT");
		Version theirs = version(OTHER, broken);

		Resolution resolution = POLICY.resolve(input(S1, null, mine, theirs, theirs));

		assertFalse(resolution.deleted());
		assertSame(theirs.payload(), resolution.payload());
		assertEquals(UnitKey.unassignedBooking(EVENT, PRESENTATION, "m"), resolution.extra().get(0).unit());
		assertEquals(Kind.DOUBLE_SALE, resolution.note().kind());
		assertEquals(OWN, resolution.note().affected());
		assertEquals(party(theirs), resolution.note().other());

		// gesperrt (kein Inhalt): ebenso
		Version locked = version(OTHER, null);
		Resolution lockedResolution = POLICY.resolve(input(S1, null, mine, locked, locked));
		assertEquals(Kind.DOUBLE_SALE, lockedResolution.note().kind());
		assertEquals(1, lockedResolution.extra().size());
		assertNull(lockedResolution.payload());
	}

	@Test
	void unreadableTheirsOnAnUnassignedUnitIsUnmerged() {
		// Eine Buchung ohne Platz kann nicht weiter ausweichen: Deren bleibt, der Vermerk sagt es diesem Rechner
		UnitKey unit = UnitKey.unassignedBooking(EVENT, PRESENTATION, "m");
		Version mine = version(OWN, booking("m", null, b -> b.setUnassigned(true)));
		ObjectNode broken = booking("m", null, b -> b.setUnassigned(true));
		broken.put("tableNumber", "keine Zahl");
		Version theirs = version(OTHER, broken);

		Resolution resolution = POLICY.resolve(input(unit, null, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty());
		assertEquals(Kind.UNMERGED, resolution.note().kind());
		assertEquals(OWN, resolution.note().affected());
		assertEquals(mine.id(), resolution.note().dropped());
	}

	@Test
	void unreadableMineIsUnmerged() {
		Version base = version(THIRD, booking("x", "s1"));
		ObjectNode broken = booking("x", "s1");
		broken.put("seatNumber", "keine Zahl");
		Version mine = version(OWN, broken);
		Version theirs = version(OTHER, booking("x", "s1", b -> b.setComment("neu")));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty());
		assertEquals(Kind.UNMERGED, resolution.note().kind());
		assertEquals(OWN, resolution.note().affected());
	}

	@Test
	void doubleSaleSurvivesBookingSyncInAnyOrder() throws IOException {
		Hall hall = new Hall();
		SeatingPlan plan = hall.addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, 1, 2);
		Table table = plan.getTables().get(0);
		Seat seat = table.getSeats().get(0);
		UnitKey unit = seat(seat.getId());
		Version mine = version(OWN, booking("m", seat.getId(), b -> b.takeSnapshot(table, seat)));
		Version theirs = version(OTHER, booking("t", seat.getId(), b -> {
			b.takeSnapshot(table, seat);
			b.setLastName("Schulz");
		}));

		Resolution resolution = POLICY.resolve(input(unit, null, mine, theirs, theirs));

		for (boolean winnerFirst : new boolean[] { true, false }) {
			Booking winner = MAPPER.treeToValue(resolution.payload(), Booking.class);
			Booking loser = MAPPER.treeToValue(resolution.extra().get(0).payload(), Booking.class);
			Presentation presentation = new Presentation();
			presentation.setSeatingPlanId(plan.getId());
			presentation.setBookings(new ArrayList<>(winnerFirst ? List.of(winner, loser) : List.of(loser, winner)));

			BookingSync.sync(presentation, plan);

			assertEquals(seat.getId(), winner.getSeatId(), "Reihenfolge Gewinner zuerst: " + winnerFirst);
			assertFalse(winner.isUnassigned());
			assertTrue(loser.isUnassigned());
			assertNull(loser.getSeatId());

			// der Gewinner wird storniert: Der Verlierer rückt nicht still auf den frei gewordenen Sitz
			winner.clearCustomerData();
			BookingSync.sync(presentation, plan);

			assertTrue(loser.isUnassigned(), "Reihenfolge Gewinner zuerst: " + winnerFirst);
			assertNull(loser.getSeatId());
			assertEquals("Muster", loser.getLastName());
			assertTrue(presentation.getBookings().contains(loser));
		}
	}

	// <-- Regel 3

	// Regel 2: Löschen gewinnt -->

	@Test
	void deletionAgainstEditBothDirections() {
		Version base = version(THIRD, booking("x", "s1"));

		// Deren hat storniert, Meine geändert
		Version mine = version(OWN, booking("x", "s1", b -> b.setComment("Abholung Freitag")));
		Version theirDeletion = deletion(OTHER);
		Resolution lost = POLICY.resolve(input(S1, base, mine, theirDeletion, theirDeletion));

		assertTrue(lost.deleted());
		assertTrue(lost.extra().isEmpty());
		assertEquals(Kind.DELETE_WINS, lost.note().kind());
		assertEquals(OWN, lost.note().affected());
		assertEquals(party(theirDeletion), lost.note().other());
		assertEquals(mine.id(), lost.note().dropped());

		// Meine hat storniert, Deren dieselbe Buchung geändert
		Version myDeletion = deletion(OWN);
		Version theirEdit = version(OTHER, booking("x", "s1", b -> b.setPaymentStatus(PaymentStatus.CASH)));
		Resolution won = POLICY.resolve(input(S1, base, myDeletion, theirEdit, theirEdit));

		assertTrue(won.deleted());
		assertTrue(won.extra().isEmpty());
		assertEquals(Kind.DELETE_WINS, won.note().kind());
		assertEquals(OTHER, won.note().affected());
		assertEquals(party(myDeletion), won.note().other());
		assertEquals(theirEdit.id(), won.note().dropped());
	}

	@Test
	void newSaleOnASeatTheirsCancelledWins() {
		// Deren storniert x; Meine verkauft auf dem Sitz eine andere Buchung y: Die Stornierung trifft y nicht
		Version base = version(THIRD, booking("x", "s1"));
		Version theirDeletion = deletion(OTHER);
		Version mine = version(OWN, booking("y", "s1", b -> b.setLastName("Neu")));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirDeletion, theirDeletion));

		assertFalse(resolution.deleted());
		assertEquals(mine.payload(), resolution.payload());
		assertNull(resolution.note());
		assertTrue(resolution.extra().isEmpty());
	}

	@Test
	void newSaleWithoutBaseAgainstACancelledSaleWins() {
		// zwei Erstverkäufe auf einem freien Sitz; Deren hat seinen Verkauf t inzwischen storniert
		Version sold = version(OTHER, booking("t", "s1"));
		Version theirDeletion = deletion(OTHER);
		Version mine = version(OWN, booking("m", "s1"));

		Resolution resolution = POLICY.resolve(input(S1, null, mine, theirDeletion, sold, theirDeletion));

		assertFalse(resolution.deleted());
		assertEquals(mine.payload(), resolution.payload());
		assertNull(resolution.note());
		assertTrue(resolution.extra().isEmpty());
	}

	@Test
	void firstVersionOfTheCancelledBookingIsAnEditThatLosesToTheCancellation() {
		// KARV-118: Meine kennt x nur abgeleitet (ohne Version) und ändert es; Deren hat x angelegt und dann storniert
		Version sold = version(THIRD, booking("x", "s1"));
		Version theirDeletion = deletion(OTHER);
		Version mine = version(OWN, booking("x", "s1", b -> b.setFirstName("Hans")));

		Resolution resolution = POLICY.resolve(input(S1, null, mine, theirDeletion, sold, theirDeletion));

		assertTrue(resolution.deleted());
		assertTrue(resolution.extra().isEmpty());
		ConflictNote note = resolution.note();
		assertEquals(Kind.DELETE_WINS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(party(theirDeletion), note.other());
		assertEquals(mine.id(), note.dropped());
	}

	@Test
	void unreadableFirstVersionOfTheCancelledBookingIsUnmerged() {
		Version sold = version(THIRD, booking("x", "s1"));
		Version theirDeletion = deletion(OTHER);
		ObjectNode broken = booking("x", "s1");
		broken.put("seatNumber", "keine Zahl");
		Version mine = version(OWN, broken);

		Resolution resolution = POLICY.resolve(input(S1, null, mine, theirDeletion, sold, theirDeletion));

		assertTrue(resolution.deleted());
		assertEquals(Kind.UNMERGED, resolution.note().kind());
		assertEquals(mine.id(), resolution.note().dropped());
	}

	@Test
	void twoFirstVersionsOfTheSameBookingDoNotDropTheirChangeSilently() {
		// beide setzen x nach einem Hallenwechsel auf denselben neuen Sitz (H3); nur Deren hat dabei den Kommentar geändert
		Version theirs = version(OTHER, booking("x", "s1", b -> b.setComment("Fenster")));
		Version mine = version(OWN, booking("x", "s1"));

		Resolution resolution = POLICY.resolve(input(S1, null, mine, theirs, theirs));

		assertFalse(resolution.deleted());
		assertEquals("Fenster", resolution.payload().get("comment").asText(), "Deren's Änderung bleibt");
	}

	@Test
	void unreadableNewSaleOnACancelledSeatIsUnmerged() {
		Version base = version(THIRD, booking("x", "s1"));
		Version theirDeletion = deletion(OTHER);
		ObjectNode broken = booking("y", "s1");
		broken.put("seatNumber", "keine Zahl");
		Version mine = version(OWN, broken);

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirDeletion, theirDeletion));

		assertTrue(resolution.deleted());
		assertTrue(resolution.extra().isEmpty());
		assertEquals(Kind.UNMERGED, resolution.note().kind());
		assertEquals(OWN, resolution.note().affected());
		assertEquals(mine.id(), resolution.note().dropped());
	}

	@Test
	void cancellationAgainstAnotherBookingKeepsIt() {
		// Meine storniert x; auf dem Sitz steht inzwischen die Buchung y: Die Stornierung trifft y nicht
		Version base = version(THIRD, booking("x", "s1"));
		Version cancelled = deletion(OTHER);
		Version resold = version(OTHER, booking("y", "s1"));
		Version myDeletion = deletion(OWN);

		Resolution resolution = POLICY.resolve(input(S1, base, myDeletion, resold, cancelled, resold));

		assertFalse(resolution.deleted());
		assertEquals(resold.payload(), resolution.payload());
		assertNull(resolution.note());
		assertTrue(resolution.extra().isEmpty());
	}

	// <-- Regel 2

	// Regel 0: beide gelöscht -->

	@Test
	void bothCancelledIsSilent() {
		Version base = version(THIRD, booking("x", "s1"));
		Version myDeletion = deletion(OWN, "save-mine");
		Version theirDeletion = deletion(OTHER, "save-theirs");
		// x lebt noch auf s4, aber aus einem anderen Speichervorgang: keine Verschiebung dieser Löschungen
		Version elsewhere = version(THIRD, "save-older", booking("x", "s4"));
		Version unrelated = version(OTHER, "save-theirs", booking("z", "s3"));
		Map<UnitKey, Sibling> siblings = siblings(Map.entry(seat("s4"), elsewhere), Map.entry(S3, unrelated));

		Resolution resolution = POLICY.resolve(input(S1, base, myDeletion, theirDeletion, siblings, theirDeletion));

		assertTrue(resolution.deleted());
		assertNull(resolution.note());
		assertTrue(resolution.extra().isEmpty());
	}

	// <-- Regel 0

	// Regel 4: gleiche Buchung -->

	@Test
	void sameBookingMergesByGroup() {
		Version base = version(THIRD, booking("x", "s1"));
		Version mine = version(OWN, booking("x", "s1", b -> b.setPaymentStatus(PaymentStatus.CASH)));
		Version theirs = version(OTHER, booking("x", "s1", b -> b.setComment("Rollstuhl mitbringen")));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertFalse(resolution.deleted());
		assertEquals(booking("x", "s1", b -> {
			b.setPaymentStatus(PaymentStatus.CASH);
			b.setComment("Rollstuhl mitbringen");
		}), resolution.payload());
		assertNull(resolution.note());
		assertTrue(resolution.extra().isEmpty());
	}

	@Test
	void sameBookingConflictingCustomerReportsCustomerGroup() {
		Version base = version(THIRD, booking("x", "s1"));
		Version mine = version(OWN, booking("x", "s1", b -> b.setLastName("Meier")));
		Version theirs = version(OTHER, booking("x", "s1", b -> b.setLastName("Schulz")));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertEquals(theirs.payload(), resolution.payload());
		assertTrue(resolution.extra().isEmpty());
		ConflictNote note = resolution.note();
		assertEquals(Kind.FIELDS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(party(theirs), note.other());
		assertEquals(mine.id(), note.dropped());
		assertEquals(1, note.fields().size());
		assertEquals("customer", note.fields().get(0).group());
		assertEquals("Meier", note.fields().get(0).mine().get("lastName").asText());
		assertEquals("Schulz", note.fields().get(0).theirs().get("lastName").asText());
	}

	// <-- Regel 4

	// Koppelregel Zahlung und Preis -->

	@Test
	void paidStatusesMatchTheModel() {
		Set<String> paid = Stream.of(PaymentStatus.values()).filter(PaymentStatus::isPaid).map(Enum::name)
				.collect(Collectors.toSet());

		assertEquals(paid, BookingPolicy.PAID_STATUSES);
	}

	/** Bar bezahlt mit dem Betrag {@code paidAmount}. */
	private static Consumer<Booking> paid(int paidAmount) {
		return b -> {
			b.setPaymentStatus(PaymentStatus.CASH);
			b.setPaidAmount(paidAmount);
		};
	}

	private static Consumer<Booking> price(Integer cents) {
		return b -> b.setPrice(cents);
	}

	private static Consumer<Booking> both(Consumer<Booking> first, Consumer<Booking> second) {
		return first.andThen(second);
	}

	/** Der Vermerk FIELDS an diesen Rechner mit genau der Gruppe "Preis", meins gegen deren. */
	private static void assertPriceNote(Resolution resolution, Version mine, Integer myPrice, Integer theirPrice) {
		ConflictNote note = resolution.note();
		assertEquals(Kind.FIELDS, note.kind());
		assertEquals(OWN, note.affected());
		assertEquals(mine.id(), note.dropped());
		assertEquals(1, note.fields().size(), note.fields().toString());
		assertEquals("price", note.fields().get(0).group());
		assertEquals(myPrice, priceIn(note.fields().get(0).mine()));
		assertEquals(theirPrice, priceIn(note.fields().get(0).theirs()));
	}

	private static Integer priceIn(ObjectNode slice) {
		return slice.hasNonNull("price") ? slice.get("price").asInt() : null;
	}

	@Test
	void paymentAgainstPriceChangeKeepsThePaymentAndNotesThePrice() {
		Version base = version(THIRD, booking("x", "s1", price(2000)));
		Version mine = version(OWN, booking("x", "s1", both(price(2000), paid(2000))));
		Version theirs = version(OTHER, booking("x", "s1", price(2500)));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertEquals(booking("x", "s1", both(price(2500), paid(2000))), resolution.payload());
		assertPriceNote(resolution, mine, 2000, 2500);
		assertEquals(party(theirs), resolution.note().other());
	}

	@Test
	void priceChangeAgainstPaymentKeepsTheirPriceAndNotesMine() {
		Version base = version(THIRD, booking("x", "s1", price(2000)));
		Version mine = version(OWN, booking("x", "s1", price(2500)));
		Version theirs = version(OTHER, booking("x", "s1", both(price(2000), paid(2000))));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertEquals(booking("x", "s1", both(price(2000), paid(2000))), resolution.payload());
		assertPriceNote(resolution, mine, 2500, 2000);
	}

	@Test
	void matchingPaymentAndPriceGiveNoNote() {
		Version base = version(THIRD, booking("x", "s1", price(2000)));
		Version mine = version(OWN, booking("x", "s1", both(price(2000), paid(2500))));
		Version theirs = version(OTHER, booking("x", "s1", price(2500)));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertEquals(booking("x", "s1", both(price(2500), paid(2500))), resolution.payload());
		assertNull(resolution.note());
	}

	@Test
	void paymentAgainstPriceChangeToStandardPriceIsNoted() {
		Version base = version(THIRD, booking("x", "s1", price(2500)));
		Version mine = version(OWN, booking("x", "s1", both(price(2500), paid(2500))));
		Version theirs = version(OTHER, booking("x", "s1", price(null)));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertEquals(booking("x", "s1", paid(2500)), resolution.payload());
		assertPriceNote(resolution, mine, 2500, null);
	}

	@Test
	void noCouplingWhenOpenOrBothChangedOrPriceAlreadyConflicting() {
		Version base = version(THIRD, booking("x", "s1", price(2000)));
		Version theirs = version(OTHER, booking("x", "s1", price(2500)));

		Version comment = version(OWN, booking("x", "s1", both(price(2000), b -> b.setComment("Fenster"))));
		assertNull(POLICY.resolve(input(S1, base, comment, theirs, theirs)).note(), "offen");

		Version paidAndPriced = version(OWN, booking("x", "s1", both(price(3000), paid(3000))));
		Resolution conflicting = POLICY.resolve(input(S1, base, paidAndPriced, theirs, theirs));
		assertPriceNote(conflicting, paidAndPriced, 3000, 2500);

		Version olderState = version(OWN, booking("x", "s1", both(price(2000), b -> b.setPaymentStatus(PaymentStatus.CASH))));
		assertNull(POLICY.resolve(input(S1, base, olderState, theirs, theirs)).note(), "älterer Stand ohne Betrag");
	}

	@Test
	void paymentMethodChangeAgainstPriceChangeKeepsMyPrice() {
		// schon bezahlt: Meine ändert den Preis (die Abweichung meldet SEA-020), Deren nur die Zahlart
		Version base = version(THIRD, booking("x", "s1", both(price(2000), paid(2000))));
		Version mine = version(OWN, booking("x", "s1", both(price(2500), paid(2000))));
		Version theirs = version(OTHER, booking("x", "s1", both(price(2000), both(paid(2000), b -> b.setPaymentStatus(PaymentStatus.CARD)))));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertEquals(booking("x", "s1", both(price(2500), both(paid(2000), b -> b.setPaymentStatus(PaymentStatus.CARD)))),
				resolution.payload());
		assertNull(resolution.note());
	}

	@Test
	void discountedPaymentAgainstPriceChangeKeepsMyPrice() {
		// Deren kassiert mit eigenem Betrag (Rabatt): Die Abweichung entsteht nicht erst beim Zusammenführen
		Version base = version(THIRD, booking("x", "s1", price(2000)));
		Version mine = version(OWN, booking("x", "s1", price(2500)));
		Version theirs = version(OTHER, booking("x", "s1", both(price(2000), paid(1500))));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertEquals(booking("x", "s1", both(price(2500), paid(1500))), resolution.payload());
		assertNull(resolution.note());
	}

	@Test
	void unknownPaymentStatusIsUnmergedWithoutFailing() {
		Version base = version(THIRD, booking("x", "s1", price(2000)));
		ObjectNode voucher = booking("x", "s1", both(price(2000), paid(2000)));
		voucher.put("paymentStatus", "VOUCHER");
		Version mine = version(OWN, voucher);
		Version theirs = version(OTHER, booking("x", "s1", price(2500)));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, theirs));

		assertEquals(Kind.UNMERGED, resolution.note().kind());
		assertEquals(theirs.payload(), resolution.payload());
	}

	@Test
	void counterpartOfThePriceNoteIsTheVersionThatSetTheirPrice() {
		Version base = version(THIRD, booking("x", "s1", price(2000)));
		Version mine = version(OWN, booking("x", "s1", both(price(2000), paid(2000))));
		Version priced = version(OTHER, booking("x", "s1", price(2500)));
		Version theirs = version(THIRD, booking("x", "s1", both(price(2500), b -> b.setComment("Fenster"))));

		Resolution resolution = POLICY.resolve(input(S1, base, mine, theirs, priced, theirs));

		assertPriceNote(resolution, mine, 2000, 2500);
		assertEquals(party(priced), resolution.note().other());
	}

	// <-- Koppelregel

	// Regel 4 mit informierter Absicht (KARV-98, Spec Abschnitt 1b) -->

	/**
	 * B gab Mia beim Zuweisen "Fenster" (Br, Bs Wiederbelebung) und änderte danach weiter auf "Fenster2" (Be auf Br); A's
	 * Zusammenführung Am hat CARD und "Fenster". Gegen Br hat B nur den Kommentar geändert, A nur die Zahlung: kein
	 * Feldkonflikt, gleich wer auflöst.
	 */
	@ParameterizedTest(name = "B löst auf: {0}")
	@ValueSource(booleans = { true, false })
	void ruleFourWithAnInformedEditMergesAgainstTheResolution(boolean bResolves) {
		TrailFixture world = new TrailFixture();
		UnitKey mia = TrailFixture.unassigned("m");
		String b = bResolves ? TrailFixture.OWN : TrailFixture.OTHER;
		String a = bResolves ? TrailFixture.OTHER : TrailFixture.OWN;
		Version m0 = world.claim(mia, world.version(TrailFixture.THIRD, null, TrailFixture.withoutPlace("m", x -> {
		})));
		Version bd = world.claim(mia, world.version(b, m0, null, TrailFixture.to(TrailFixture.seat("s1"))));
		Version ae = world.beside(mia, world.version(a, m0, TrailFixture.withoutPlace("m",
				x -> x.setPaymentStatus(PaymentStatus.CARD))));
		ObjectNode merged = TrailFixture.withoutPlace("m", x -> {
			x.setPaymentStatus(PaymentStatus.CARD);
			x.setComment("Fenster");
		});
		ObjectNode revived = TrailFixture.withoutPlace("m", x -> x.setComment("Fenster"));
		ObjectNode edited = TrailFixture.withoutPlace("m", x -> x.setComment("Fenster2"));
		ConflictInput input;
		if (bResolves) {
			world.claim(mia, world.version(a, bd, merged, VersionMarks.RESOLUTION));
			Version br = world.beside(mia, world.version(b, bd, revived, VersionMarks.RESOLUTION));
			Version be = world.beside(mia, world.version(b, br, edited));
			input = world.input(mia, m0, List.of(br, be));
		} else {
			Version br = world.claim(mia, world.version(b, bd, revived, VersionMarks.RESOLUTION));
			world.claim(mia, world.version(b, br, edited));
			Version am = world.beside(mia, world.version(a, ae, merged, VersionMarks.RESOLUTION));
			input = world.input(mia, m0, List.of(am));
		}

		Resolution resolution = new BookingPolicy().resolve(input);

		assertEquals("Fenster2", resolution.payload().get("comment").asText());
		assertEquals("CARD", resolution.payload().get("paymentStatus").asText());
		assertNull(resolution.note(), "kein Feldkonflikt gegen sich selbst");
	}

	/**
	 * 1b ohne Absenkung (benannte Verhaltensänderung): Ein fremder Umzug scheiterte, E9 belebte die Quelle wieder (R), ein
	 * anderer Rechner bearbeitete die Buchung dort (F). Ein eigener veralteter Stand verliert dagegen: Fs Änderung bleibt.
	 */
	@Test
	void editOnARevivalSurvivesAgainstAStaleOwnVersion() {
		TrailFixture world = new TrailFixture();
		UnitKey s1 = TrailFixture.seat("s1");
		UnitKey s4 = TrailFixture.seat("s4");
		Version base = world.claim(s1, world.version(TrailFixture.THIRD, null, TrailFixture.booking("m", "s1")));
		Version removal = world.claim(s1, world.version(TrailFixture.OTHER, base, null, TrailFixture.to(s4)));
		world.claim(s4, world.version(TrailFixture.THIRD, null, TrailFixture.booking("y", "s4")));
		world.beside(s4, world.version(TrailFixture.OTHER, null, TrailFixture.booking("m", "s4"), TrailFixture.from(s1)));
		Version revival = world.claim(s1, world.version(TrailFixture.OTHER, removal, TrailFixture.booking("m", "s1"),
				VersionMarks.RESOLUTION));
		world.claim(s1, world.version(TrailFixture.THIRD, revival, TrailFixture.booking("m", "s1",
				x -> x.setComment("F"))));
		Version mine = world.beside(s1, world.version(TrailFixture.OWN, base, TrailFixture.booking("m", "s1",
				x -> x.setPaymentStatus(PaymentStatus.CARD))));

		Resolution resolution = new BookingPolicy().resolve(world.input(s1, base, List.of(mine)));

		assertEquals("F", resolution.payload().get("comment").asText(), "Fs Bearbeitung bleibt");
		assertEquals("CARD", resolution.payload().get("paymentStatus").asText(), "die eigene Änderung auch");
	}
}
