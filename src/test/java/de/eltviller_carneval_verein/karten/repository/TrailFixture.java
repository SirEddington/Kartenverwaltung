package de.eltviller_carneval_verein.karten.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.TrailCollector;
import de.eltviller_carneval_verein.karten.store.TrailUnit;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;
import de.eltviller_carneval_verein.karten.store.VersionMarks;

/**
 * Ordner von Buchungs-Einheiten im Kleinen für Tests der Umzugsspur (KARV-93): je Einheit die Kette und alle Versionen,
 * daraus die Spur über {@link TrailCollector} wie im Resolver. Buchungen wie vom Repository geschrieben.
 */
final class TrailFixture {

	static final String OWN = "ownmach1";
	static final String OTHER = "othermac";
	static final String THIRD = "thirdmac";
	static final String EVENT = "event-1";
	static final String PRESENTATION = "pres-1";

	private final Map<UnitKey, List<Version>> chains = new HashMap<>();
	private final Map<UnitKey, List<Version>> folders = new HashMap<>();
	private final Map<UnitKey, VersionId> currents = new HashMap<>();
	private int counter;

	static UnitKey seat(String seatId) {
		return UnitKey.booking(EVENT, PRESENTATION, seatId);
	}

	static UnitKey unassigned(String bookingId) {
		return UnitKey.unassignedBooking(EVENT, PRESENTATION, bookingId);
	}

	/** Eine Buchung auf dem Sitz: Kunde Max Muster, danach die Änderungen aus {@code edit}. */
	static ObjectNode booking(String id, String seatId, Consumer<Booking> edit) {
		Booking booking = new Booking();
		booking.setId(id);
		booking.setSeatId(seatId);
		booking.setRowLabel("A");
		booking.setTableNumber(1);
		booking.setSeatNumber(1);
		booking.setLastName("Muster");
		booking.setFirstName("Max");
		booking.setEMail("max@example.org");
		edit.accept(booking);
		return UnitMapper.normalize(booking);
	}

	static ObjectNode booking(String id, String seatId) {
		return booking(id, seatId, b -> {
		});
	}

	/** Eine Buchung ohne Platz, danach die Änderungen aus {@code edit}. */
	static ObjectNode withoutPlace(String id, Consumer<Booking> edit) {
		return booking(id, null, b -> {
			b.setUnassigned(true);
			b.setRowLabel("");
			b.setTableNumber(0);
			b.setSeatNumber(0);
			edit.accept(b);
		});
	}

	static VersionMarks to(UnitKey target) {
		return VersionMarks.NONE.withMovedTo(target);
	}

	static VersionMarks from(UnitKey source) {
		return VersionMarks.NONE.withMovedFrom(source);
	}

	/** Eine Version auf {@code parent} (oder ohne); ohne Inhalt eine Löschung. */
	Version version(String machine, String saveId, Version parent, ObjectNode payload, VersionMarks marks) {
		counter++;
		return new Version(1, VersionId.create(1_800_000_000_000L + counter, machine, counter),
				parent == null ? List.of() : List.of(parent.id()), machine, "Kasse " + machine, "Bediener " + machine,
				Instant.EPOCH, saveId, payload == null, payload, null, Map.of(), marks);
	}

	Version version(String machine, Version parent, ObjectNode payload, VersionMarks marks) {
		return version(machine, "save-" + (counter + 1), parent, payload, marks);
	}

	Version version(String machine, Version parent, ObjectNode payload) {
		return version(machine, parent, payload, VersionMarks.NONE);
	}

	/** Hängt die Version an die Kette der Einheit. */
	Version claim(UnitKey unit, Version version) {
		chains.computeIfAbsent(unit, key -> new ArrayList<>()).add(version);
		return beside(unit, version);
	}

	/** Legt die Version nur in den Ordner (verloren oder ausstehend). */
	Version beside(UnitKey unit, Version version) {
		folders.computeIfAbsent(unit, key -> new ArrayList<>()).add(version);
		return version;
	}

	/** Setzt den aktuellen Stand (eine eigene ausstehende Version). */
	void current(UnitKey unit, Version version) {
		currents.put(unit, version.id());
	}

	List<Version> chain(UnitKey unit) {
		return chains.getOrDefault(unit, List.of());
	}

	/** Die Spur wie im Resolver. */
	Map<UnitKey, TrailUnit> trail(UnitKey unit, Version base, List<Version> stretch, List<Version> since) {
		try {
			return TrailCollector.collect(unit, base, stretch, since, key -> {
				List<Version> chain = chain(key);
				VersionId current = currents.getOrDefault(key, chain.isEmpty() ? null : chain.get(chain.size() - 1).id());
				return new TrailCollector.Folder(chain, folders.getOrDefault(key, List.of()), current);
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Die Eingabe für die verlorene Strecke dieses Rechners ({@link #OWN}) an der Einheit: Basis und Deren aus der Kette,
	 * Spur über alle Ordner.
	 */
	ConflictInput input(UnitKey unit, Version base, List<Version> stretch) {
		List<Version> chain = chain(unit);
		int at = base == null ? -1 : chain.indexOf(base);
		List<Version> since = chain.subList(at + 1, chain.size());
		return new ConflictInput(unit, base, stretch.get(stretch.size() - 1), chain.get(chain.size() - 1), since, OWN,
				Map.of(), stretch, trail(unit, base, stretch, since));
	}
}
