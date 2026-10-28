package de.eltviller_carneval_verein.karten.repository;

import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ConflictInput.Sibling;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ConflictNote.FieldConflict;
import de.eltviller_carneval_verein.karten.store.ConflictNote.Kind;
import de.eltviller_carneval_verein.karten.store.ConflictPolicy;
import de.eltviller_carneval_verein.karten.store.Resolution;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;

/**
 * Die Fachregel für Buchungen (Sitz-Einheit oder {@code _<ID>}). Die Regeln gelten in dieser Reihenfolge:
 * <ol start="0">
 * <li>Beide gelöscht (Verschieben gegen Storno): siehe {@link #bothDeleted}.</li>
 * <li>Gleich ({@link PolicyRules#equal}).</li>
 * <li>Löschen gewinnt ({@link PolicyRules#deleteWins}); eine Stornierung trifft nur dieselbe Buchung (Buchungs-ID der
 * Basis), sonst siehe {@link #cancelledAnotherBooking}.</li>
 * <li>Anderer Inhalt auf dem Sitz: Storno und Neuverkauf oder Doppelverkauf, siehe {@link #otherBooking}.</li>
 * <li>Gleiche Buchung: Dreiwegevergleich je Feldgruppe ({@link FieldGroups#booking()}).</li>
 * </ol>
 * Ein unlesbares oder gesperrtes "Deren" führt nicht zu UNMERGED, sondern zum Doppelverkauf (K1): Die eigene Buchung
 * weicht "ohne Platz" aus, statt zu verschwinden. Zustandslos; läuft auf dem Hintergrund-Thread des Versionsspeichers und
 * kennt weder Modell noch Repository, alle Eingaben sind Kopien.
 */
public final class BookingPolicy implements ConflictPolicy {

	private static final String ID = "id";
	private static final String SEAT_ID = "seatId";
	private static final String ROW_LABEL = "rowLabel";
	private static final String TABLE_NUMBER = "tableNumber";
	private static final String SEAT_NUMBER = "seatNumber";
	private static final String UNASSIGNED = "unassigned";
	/** Der Platz im Vermerk eines Doppelverkaufs, für die Anzeige im Dialog. */
	private static final List<String> PLACE = List.of(SEAT_ID, ROW_LABEL, TABLE_NUMBER, SEAT_NUMBER);
	/** Die Feldgruppe "Platz" für den Vermerk, wenn beide Seiten verschoben haben. */
	private static final String PLACE_GROUP = "place";

	@Override
	public Resolution resolve(ConflictInput input) {
		Resolution resolution = bothDeleted(input);
		if (resolution == null) {
			resolution = PolicyRules.equal(input);
		}
		if (resolution == null) {
			resolution = PolicyRules.deleteWins(input, theirs -> isSameBooking(input, theirs));
		}
		if (resolution == null) {
			resolution = cancelledAnotherBooking(input);
		}
		if (resolution == null) {
			resolution = otherBooking(input);
		}
		if (resolution == null) {
			resolution = PolicyRules.mergeFields(input, FieldGroups.booking());
		}
		return resolution;
	}

	/**
	 * Regel 0, beide gelöscht: Entscheidend ist, wo die Buchungs-ID der Basis danach noch lebt. Eine Seite hat verschoben,
	 * wenn eine Geschwister-Einheit mit dieser ID im selben Speichervorgang wie ihre Löschung entstand (das letzte Glied
	 * ihrer Kette trägt dessen {@code saveId}); sonst hat sie storniert.
	 * <ul>
	 * <li>Verschoben gegen storniert: Storno gewinnt, die verschobene Einheit bekommt eine Löschung; betroffen ist der
	 * Rechner, der verschoben hat (Vermerk DELETE_WINS, {@code dropped} ist die Version der verschobenen Einheit). Das
	 * gilt in beide Richtungen.</li>
	 * <li>Beide verschoben: Die Verschiebung von "Deren" gilt, die eigene verschobene Einheit wird gelöscht; Vermerk FIELDS
	 * mit der Gruppe "Platz" (beide Plätze aus den verschobenen Einheiten, {@code dropped} ist die eigene).</li>
	 * <li>Beide storniert: Löschung ohne Vermerk.</li>
	 * </ul>
	 * Die eigene verschobene Einheit ist immer eigener Inhalt: Ihr letztes Glied stammt aus dem eigenen Speichervorgang.
	 * Lebt dort inzwischen fremder Inhalt, gilt sie nicht als verschoben und wird nicht angefasst.
	 *
	 * @return die Auflösung, oder {@code null}, wenn nicht beide Seiten gelöscht sind
	 */
	private static Resolution bothDeleted(ConflictInput input) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		if (!mine.deleted() || !theirs.deleted()) {
			return null;
		}
		Resolution cancelled = new Resolution(theirs.payload(), true, List.of(), null);
		String bookingId = idOf(input.base());
		if (bookingId == null) {
			return cancelled;
		}
		// Ist "Deren" die Zusammenführung eines dritten Rechners, gehört die Verschiebung zur ursprünglichen Löschung
		Version deletion = FieldMerge.introducedBy(input.chainSinceBase(), Version::deleted);
		Version theirDeletion = deletion != null ? deletion : theirs;
		UnitSnapshot myCopy = movedCopy(input, bookingId, mine.saveId());
		UnitSnapshot theirCopy = movedCopy(input, bookingId, theirDeletion.saveId());

		if (myCopy != null && theirCopy != null) {
			List<String> place = FieldGroups.booking().get(PLACE_GROUP);
			FieldConflict conflict = new FieldConflict(PLACE_GROUP, FieldMerge.slice(myCopy.payload(), place),
					FieldMerge.slice(theirCopy.payload(), place));
			ConflictNote note = new ConflictNote(Kind.FIELDS, input.ownMachineId(), PolicyRules.party(theirDeletion),
					List.of(conflict), null, null, myCopy.version());
			return new Resolution(theirs.payload(), true, List.of(deletionOf(myCopy)), note);
		}
		if (myCopy != null) {
			ConflictNote note = new ConflictNote(Kind.DELETE_WINS, input.ownMachineId(), PolicyRules.party(theirDeletion),
					List.of(), null, null, myCopy.version());
			return new Resolution(theirs.payload(), true, List.of(deletionOf(myCopy)), note);
		}
		if (theirCopy != null) {
			ConflictNote note = new ConflictNote(Kind.DELETE_WINS, theirDeletion.machine(), PolicyRules.party(mine),
					List.of(), null, null, theirCopy.version());
			return new Resolution(theirs.payload(), true, List.of(deletionOf(theirCopy)), note);
		}
		return cancelled;
	}

	/**
	 * Die Geschwister-Einheit, in die ein Speichervorgang die Buchung verschoben hat: nicht gelöscht, mit der Buchungs-ID,
	 * und das letzte Glied ihrer Kette stammt aus diesem Speichervorgang. Bei mehreren (sollte nicht vorkommen) die erste
	 * nach Ordner, damit eine Wiederholung nach einem Absturz dasselbe entscheidet.
	 *
	 * @return ihr Stand, oder {@code null}, wenn es keine gibt
	 */
	private static UnitSnapshot movedCopy(ConflictInput input, String bookingId, String saveId) {
		if (saveId == null) {
			return null;
		}
		return input.siblings().values().stream()
				.filter(sibling -> sibling.head() != null && saveId.equals(sibling.head().saveId()))
				.map(Sibling::snapshot)
				.filter(snapshot -> snapshot.version() != null && !snapshot.deleted()
						&& bookingId.equals(text(snapshot.payload(), ID)))
				.min(Comparator.comparing(snapshot -> snapshot.unit().relativePath()))
				.orElse(null);
	}

	/** Die Löschung einer Geschwister-Einheit auf ihrem aktuellen Stand. */
	private static Change deletionOf(UnitSnapshot sibling) {
		return new Change(sibling.unit(), List.of(sibling.version()), null, true);
	}

	/**
	 * Ob "Deren" dieselbe Buchung ist wie die Basis (gleiche Buchungs-ID); nur dann darf eine eigene Stornierung sie
	 * löschen. Ein unlesbares "Deren" ist es nicht.
	 */
	private static boolean isSameBooking(ConflictInput input, Version theirs) {
		String bookingId = idOf(input.base());
		return bookingId != null && PolicyRules.isReadable(input, theirs) && bookingId.equals(idOf(theirs));
	}

	/**
	 * "Meine" ist eine Stornierung, "Deren" aber nicht dieselbe Buchung (sonst hätte Regel 2 gegriffen): Auf dem Sitz steht
	 * inzwischen eine andere Buchung, die die Stornierung nicht trifft; sie bleibt ohne Vermerk. Ist "Deren" unlesbar, ist
	 * das nicht zu entscheiden: "Deren" bleibt, der Vermerk UNMERGED sagt es diesem Rechner.
	 *
	 * @return die Auflösung, oder {@code null}, wenn "Meine" nicht gelöscht ist
	 */
	private static Resolution cancelledAnotherBooking(ConflictInput input) {
		if (!input.mine().deleted()) {
			return null;
		}
		if (!PolicyRules.isReadable(input, input.theirs())) {
			return PolicyRules.unmerged(input);
		}
		return new Resolution(input.theirs().payload(), false, List.of(), null);
	}

	/**
	 * Regel 3, beide Seiten mit Inhalt:
	 * <ul>
	 * <li>Verschiedene Buchungs-IDs, "Meine" hat die der Basis, und die Kette seit der Basis enthält eine Löschung (storniert,
	 * danach neu verkauft): Löschen gewinnt, "Deren" gilt (Vermerk DELETE_WINS an diesen Rechner).</li>
	 * <li>Sonst verschiedene IDs, oder "Deren" unlesbar oder gesperrt (K1): {@link #doubleSale Doppelverkauf}.</li>
	 * </ul>
	 * Ist "Meine" unlesbar, lässt sich die Buchung nicht verschieben: "Deren" bleibt, Vermerk UNMERGED.
	 *
	 * @return die Auflösung, oder {@code null} bei derselben Buchung auf beiden Seiten (Regel 4)
	 */
	private static Resolution otherBooking(ConflictInput input) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		String mineId = idOf(mine);
		if (mineId == null || !PolicyRules.isReadable(input, mine)) {
			return PolicyRules.unmerged(input);
		}
		boolean theirsReadable = PolicyRules.isReadable(input, theirs);
		if (theirsReadable && mineId.equals(idOf(theirs))) {
			return null;
		}
		if (theirsReadable && mineId.equals(idOf(input.base()))
				&& input.chainSinceBase().stream().anyMatch(Version::deleted)) {
			Version deletion = FieldMerge.introducedBy(input.chainSinceBase(), Version::deleted);
			ConflictNote note = new ConflictNote(Kind.DELETE_WINS, input.ownMachineId(), PolicyRules.party(deletion),
					List.of(), null, null, mine.id());
			return new Resolution(theirs.payload(), false, List.of(), note);
		}
		return doubleSale(input, mineId);
	}

	/**
	 * Doppelverkauf: Der Sitz behält "Deren" unangetastet. "Meine" wird als neue Einheit {@code _<meine ID>} geschrieben,
	 * mit allen Kundendaten, aber "ohne Platz": {@code unassigned}, Sitz-ID, Reihe, Tisch und Sitz geleert wie bei einer
	 * neuen Buchung. Sonst ordnete {@code BookingSync} den Sitz je nach Reihenfolge der Verlierer-Buchung zu, und sobald er
	 * frei wird, setzte es sie still dorthin zurück. Der bisherige Platz steht nur im Vermerk DOUBLE_SALE.
	 * <p>
	 * Der Schlüssel {@code _<ID>} ist fest, damit eine Wiederholung nach einem Absturz dieselbe Einheit trifft. Ist sie
	 * schon gelöscht (die Buchung stand früher ohne Platz und wurde von dort zugewiesen), baut die neue Version auf der
	 * Löschung auf; eine Erstversion übersprünge die Auflösung, weil die Einheit schon Versionen hat. Ist die Einheit selbst
	 * schon {@code _<ID>}, kann die Buchung nicht weiter ausweichen: "Deren" bleibt, Vermerk UNMERGED.
	 */
	private static Resolution doubleSale(ConflictInput input, String mineId) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		UnitKey unit = input.unit();
		UnitKey moved = UnitKey.unassignedBooking(unit.eventId(), unit.presentationId(), mineId);
		if (moved.equals(unit)) {
			return PolicyRules.unmerged(input);
		}
		ObjectNode withoutPlace = mine.payload().deepCopy();
		withoutPlace.put(UNASSIGNED, true);
		withoutPlace.putNull(SEAT_ID);
		withoutPlace.put(ROW_LABEL, "");
		withoutPlace.put(TABLE_NUMBER, 0);
		withoutPlace.put(SEAT_NUMBER, 0);
		Sibling existing = input.siblings().get(moved);
		List<VersionId> parents = existing != null && existing.snapshot().version() != null
				&& existing.snapshot().deleted() ? List.of(existing.snapshot().version()) : List.of();
		Change extra = new Change(moved, parents, withoutPlace, false);

		String theirsId = idOf(theirs);
		Version introducer = theirsId == null ? null
				: FieldMerge.introducedBy(input.chainSinceBase(), version -> theirsId.equals(idOf(version)));
		ConflictNote note = new ConflictNote(Kind.DOUBLE_SALE, input.ownMachineId(),
				PolicyRules.party(introducer != null ? introducer : theirs), List.of(), moved,
				FieldMerge.slice(mine.payload(), PLACE), mine.id());
		return new Resolution(theirs.payload(), false, List.of(extra), note);
	}

	/** Die Buchungs-ID einer Version mit Inhalt; {@code null} ohne Version, bei einer Löschung oder ohne ID. */
	private static String idOf(Version version) {
		return version == null || version.deleted() ? null : text(version.payload(), ID);
	}

	private static String text(ObjectNode payload, String field) {
		JsonNode value = payload == null ? null : payload.get(field);
		return value != null && value.isTextual() ? value.asText() : null;
	}
}
