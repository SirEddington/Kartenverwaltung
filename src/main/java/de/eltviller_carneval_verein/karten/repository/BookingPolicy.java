package de.eltviller_carneval_verein.karten.repository;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ConflictInput.Sibling;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ConflictNote.FieldConflict;
import de.eltviller_carneval_verein.karten.store.ConflictNote.Kind;
import de.eltviller_carneval_verein.karten.store.ConflictNote.Party;
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
 * <li>Löschung gegen Änderung: Trägt "Meine" nicht die Buchung der Basis, gilt sie ({@link #saleOnCancelledSeat}). War
 * die Löschung ein Verschieben, geht die Änderung an die verschobene Kopie ({@link #movedAgainstEdit}). Sonst gewinnt die
 * Löschung ({@link PolicyRules#deleteWins}), aber nur gegen dieselbe Buchung ({@link #cancelledAnotherBooking}).</li>
 * <li>Verschiedene Buchungen auf dem Sitz: entschieden danach, wer die Buchung der Basis trägt, siehe
 * {@link #otherBooking}.</li>
 * <li>Gleiche Buchung: Dreiwegevergleich je Feldgruppe ({@link FieldGroups#booking()}).</li>
 * </ol>
 * Eine Buchungs-ID lebt danach nie in zwei Einheiten zugleich. Ein unlesbares oder gesperrtes "Deren" führt nicht zu
 * UNMERGED, sondern zum Doppelverkauf (K1): Die eigene Buchung weicht "ohne Platz" aus, statt zu verschwinden; außer auf
 * einer Einheit {@code _<ID>}, dort kann sie nicht ausweichen, und es bleibt bei UNMERGED. Zustandslos; läuft auf dem
 * Hintergrund-Thread des Versionsspeichers und kennt weder Modell noch Repository, alle Eingaben sind Kopien.
 */
public final class BookingPolicy implements ConflictPolicy {

	private static final String SEAT_ID = "seatId";
	private static final String ROW_LABEL = "rowLabel";
	private static final String TABLE_NUMBER = "tableNumber";
	private static final String SEAT_NUMBER = "seatNumber";
	private static final String UNASSIGNED = "unassigned";
	/** Der Platz im Vermerk eines Doppelverkaufs, für die Anzeige im Dialog. */
	private static final List<String> PLACE = List.of(SEAT_ID, ROW_LABEL, TABLE_NUMBER, SEAT_NUMBER);
	/** Die Feldgruppe "Platz". */
	private static final String PLACE_GROUP = "place";

	@Override
	public Resolution resolve(ConflictInput input) {
		Resolution resolution = bothDeleted(input);
		if (resolution == null) {
			resolution = PolicyRules.equal(input);
		}
		if (resolution == null) {
			resolution = saleOnCancelledSeat(input);
		}
		if (resolution == null) {
			resolution = movedAgainstEdit(input);
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
	 * wenn sie im selben Speichervorgang eine {@link #movedCopy Kopie} angelegt hat. Sonst hat sie storniert.
	 * <ul>
	 * <li>Verschoben gegen storniert: Storno gewinnt, die verschobene Einheit bekommt eine Löschung; betroffen ist der
	 * Rechner, der verschoben hat (Vermerk DELETE_WINS, {@code dropped} ist die Version der verschobenen Einheit). Das
	 * gilt in beide Richtungen.</li>
	 * <li>Beide an verschiedene Sitze verschoben: Die Verschiebung von "Deren" gilt, die eigene verschobene Einheit wird
	 * gelöscht; Vermerk FIELDS mit der Gruppe "Platz" (beide Plätze aus den verschobenen Einheiten, {@code dropped} ist die
	 * eigene).</li>
	 * <li>Beide an denselben Sitz verschoben: Sie sind sich einig; Löschung ohne Vermerk, sonst nichts.</li>
	 * <li>Beide storniert, oder nicht zu entscheiden (keine Geschwister-Einheit trägt die Buchung): Löschung ohne Vermerk,
	 * keine weitere Löschung. Eine verkaufte Buchung wird nie auf Verdacht gelöscht.</li>
	 * </ul>
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
		Version theirDeletion = deletionIn(input);
		UnitSnapshot myCopy = movedCopy(input, bookingId, mine.saveId());
		UnitSnapshot theirCopy = movedCopy(input, bookingId, theirDeletion.saveId());

		if (myCopy != null && theirCopy != null) {
			if (myCopy.unit().equals(theirCopy.unit())) {
				return cancelled;
			}
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
	 * Die Löschung in der Kette seit der Basis, die "Deren" eingebracht hat: Ist "Deren" die Zusammenführung eines dritten
	 * Rechners, gehört ein Verschieben zur ursprünglichen Löschung. Ohne Löschung in der Kette "Deren" selbst.
	 */
	private static Version deletionIn(ConflictInput input) {
		Version deletion = FieldMerge.introducedBy(input.chainSinceBase(), Version::deleted);
		return deletion != null ? deletion : input.theirs();
	}

	/**
	 * Die Kopie, die ein Speichervorgang angelegt hat, als er die Buchung verschob: eine {@link #livingCopies lebende
	 * Kopie}, in deren Ordner eine Version aus diesem Speichervorgang liegt ({@link Sibling#saveIds()}, Ruling R22), auch
	 * wenn sie seitdem geändert wurde, noch aussteht oder dort verloren hat.
	 * <p>
	 * Nicht erkannt wird ein Verschieben, dessen Kopie keine Version aus dem Speichervorgang der Löschung trägt; die Seite
	 * gilt dann als storniert:
	 * <ul>
	 * <li>"Meine" ist selbst eine Zusammenführung des Auflösers (er schreibt mit einem eigenen Speichervorgang je Strecke):
	 * Ein Verschieben, das sie übernommen hat, gehört zu einem anderen Speichervorgang.</li>
	 * <li>Die Kopie wurde danach weiter verschoben (s1 → s2 → s3): Im Ordner von s3 liegt der Speichervorgang der Löschung
	 * von s1 nicht.</li>
	 * <li>Selten, drei Rechner: Das eigene Verschieben nach s2 verlor dort gegen einen Verkauf. Die Buchung lebt erst nach
	 * der Auflösung von s2 unter {@code _<ID>}, geschrieben in deren Speichervorgang.</li>
	 * </ul>
	 *
	 * @return ihr Stand, oder {@code null}, wenn es keine gibt
	 */
	private static UnitSnapshot movedCopy(ConflictInput input, String bookingId, String saveId) {
		if (saveId == null) {
			return null;
		}
		return first(livingCopies(input, bookingId).filter(sibling -> sibling.saveIds().contains(saveId)));
	}

	/** Irgendeine {@link #livingCopies lebende Kopie} der Buchung, oder {@code null}. */
	private static UnitSnapshot livingCopy(ConflictInput input, String bookingId) {
		return first(livingCopies(input, bookingId));
	}

	/** Die Geschwister-Einheiten, die die Buchung tragen (nicht gelöscht, gleiche Buchungs-ID). */
	private static Stream<Sibling> livingCopies(ConflictInput input, String bookingId) {
		return input.siblings().values().stream().filter(sibling -> {
			UnitSnapshot snapshot = sibling.snapshot();
			return snapshot.version() != null && !snapshot.deleted() && bookingId.equals(text(snapshot.payload()));
		});
	}

	/** Bei mehreren (sollte nicht vorkommen) die erste nach Ordner, damit eine Wiederholung dasselbe entscheidet. */
	private static UnitSnapshot first(Stream<Sibling> siblings) {
		return siblings.map(Sibling::snapshot).min(Comparator.comparing(snapshot -> snapshot.unit().relativePath()))
				.orElse(null);
	}

	/** Die Löschung einer Geschwister-Einheit auf ihrem aktuellen Stand. */
	private static Change deletionOf(UnitSnapshot sibling) {
		return new Change(sibling.unit(), List.of(sibling.version()), null, true);
	}

	/**
	 * Überträgt eine Änderung der Buchung auf ihre Kopie in einer anderen Einheit, statt sie zu verwerfen oder die Buchung
	 * ein zweites Mal anzulegen: Dreiwegevergleich gegen die Basis mit {@code change} auf der einen und der Kopie auf der
	 * anderen Seite; die Gruppe "Platz" bleibt die der Kopie, bei strittigen Gruppen gilt die Kopie. Ergibt das einen
	 * anderen Inhalt, bekommt die Kopie eine neue Version auf ihrem aktuellen Stand. Strittige Gruppen kommen in einen
	 * Vermerk FIELDS mit {@code moved} = Einheit der Kopie (dort lebt die Buchung, nicht auf dem Sitz des Vermerks).
	 *
	 * @param payload  die Fassung der Einheit selbst
	 * @param deleted  ob die Einheit selbst gelöscht wird
	 * @param copy     die Kopie
	 * @param change   der Inhalt, dessen Änderung übertragen wird
	 * @param affected der Rechner, dessen Änderung bei strittigen Gruppen weicht
	 * @param other    die Gegenseite des Vermerks
	 * @param dropped  die Version mit der weichenden Änderung
	 */
	private static Resolution transfer(ConflictInput input, ObjectNode payload, boolean deleted, UnitSnapshot copy,
			ObjectNode change, String affected, Party other, VersionId dropped) {
		ObjectNode side = change.deepCopy();
		for (String field : FieldGroups.booking().get(PLACE_GROUP)) {
			JsonNode value = copy.payload().get(field);
			if (value == null) {
				side.remove(field);
			} else {
				side.set(field, value.deepCopy());
			}
		}
		Version base = input.base();
		FieldMerge.Result result = FieldMerge.merge(base == null || base.deleted() ? null : base.payload(), side,
				copy.payload(), FieldGroups.booking());
		List<Change> extra = result.merged().equals(copy.payload()) ? List.of()
				: List.of(new Change(copy.unit(), List.of(copy.version()), result.merged(), false));
		ConflictNote note = result.conflicts().isEmpty() ? null
				: new ConflictNote(Kind.FIELDS, affected, other, result.conflicts(), copy.unit(), null, dropped);
		return new Resolution(payload, deleted, extra, note);
	}

	/**
	 * Regel 2 nur für dieselbe Buchung (Ruling R23): "Deren" ist eine Löschung, "Meine" aber nicht die Buchung der Basis
	 * (andere Buchungs-ID, oder keine Basis): "Meine" ist ein neuer Verkauf auf dem Sitz, den "Deren" storniert oder frei
	 * gemacht hat. Die Stornierung trifft ihn nicht; "Meine" gilt ohne Vermerk. Ist "Meine" unlesbar, bleibt die Löschung
	 * mit dem Vermerk UNMERGED.
	 *
	 * @return die Auflösung, oder {@code null}, wenn "Deren" nicht gelöscht ist oder "Meine" die Buchung der Basis trägt
	 */
	private static Resolution saleOnCancelledSeat(ConflictInput input) {
		Version mine = input.mine();
		if (!input.theirs().deleted() || mine.deleted()) {
			return null;
		}
		String bookingId = idOf(input.base());
		if (bookingId != null && bookingId.equals(idOf(mine))) {
			return null;
		}
		if (!PolicyRules.isReadable(input, mine)) {
			return PolicyRules.unmerged(input);
		}
		return new Resolution(mine.payload(), false, List.of(), null);
	}

	/**
	 * Regel 2 mit Verschieben (Ruling R25): Die Löschung der Buchung der Basis war ein Verschieben, die andere Seite hat
	 * die Buchung hier geändert. Die Änderung geht an die verschobene Kopie ({@link #transfer}), die Einheit selbst bleibt
	 * gelöscht.
	 * <ul>
	 * <li>"Deren" gelöscht, "Meine" geändert, und die Buchung lebt in einer Geschwister-Einheit: betroffen ist bei strittigen
	 * Gruppen dieser Rechner.</li>
	 * <li>"Meine" gelöscht, "Deren" dieselbe Buchung geändert, und die Buchung lebt in einer Kopie aus dem eigenen
	 * Speichervorgang: betroffen ist bei strittigen Gruppen der Rechner von "Deren".</li>
	 * </ul>
	 *
	 * @return die Auflösung, oder {@code null}, wenn keine solche Kopie die Buchung trägt
	 */
	private static Resolution movedAgainstEdit(ConflictInput input) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		String bookingId = idOf(input.base());
		if (bookingId == null || mine.deleted() == theirs.deleted()) {
			return null;
		}
		if (theirs.deleted()) {
			// "Meine" trägt die Buchung der Basis, sonst hätte saleOnCancelledSeat entschieden
			UnitSnapshot copy = livingCopy(input, bookingId);
			if (copy == null || !PolicyRules.isReadable(input, mine)) {
				return null;
			}
			return transfer(input, theirs.payload(), true, copy, mine.payload(), input.ownMachineId(),
					PolicyRules.party(deletionIn(input)), mine.id());
		}
		if (!isSameBooking(input, theirs)) {
			return null;
		}
		UnitSnapshot copy = movedCopy(input, bookingId, mine.saveId());
		if (copy == null) {
			return null;
		}
		return transfer(input, null, true, copy, theirs.payload(), theirs.machine(), PolicyRules.party(mine),
				theirs.id());
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
	 * Regel 3, beide Seiten mit Inhalt, entschieden danach, wer die Buchung der Basis trägt (Ruling R25). Gespeichert wird
	 * je Event; "x stornieren und y verkaufen" oder "x verschieben und y verkaufen" in einem Speichervorgang schreibt den
	 * Sitz ohne Löschversion von x auf y.
	 * <ul>
	 * <li>Ist "Meine" unlesbar, lässt sich nichts verschieben oder übertragen: "Deren" bleibt, Vermerk UNMERGED.</li>
	 * <li>"Deren" unlesbar oder gesperrt (K1): {@link #doubleSale Doppelverkauf}.</li>
	 * <li>Gleiche Buchungs-ID: {@code null}, Regel 4.</li>
	 * <li>"Meine" trägt die Buchung der Basis, "Deren" hat sie ersetzt: {@link #replacedByTheirs}.</li>
	 * <li>"Deren" trägt die Buchung der Basis, "Meine" hat sie ersetzt: {@link #replacedByMine}.</li>
	 * <li>Keine Seite trägt sie (keine Basis, oder beide haben sie durch verschiedene neue Buchungen ersetzt):
	 * Doppelverkauf.</li>
	 * </ul>
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
		if (!PolicyRules.isReadable(input, theirs)) {
			return doubleSale(input, mineId);
		}
		String theirsId = idOf(theirs);
		if (mineId.equals(theirsId)) {
			return null;
		}
		String bookingId = idOf(input.base());
		if (mineId.equals(bookingId)) {
			return replacedByTheirs(input, bookingId, theirsId);
		}
		if (bookingId != null && bookingId.equals(theirsId)) {
			return replacedByMine(input, bookingId);
		}
		return doubleSale(input, mineId);
	}

	/**
	 * "Deren" hat die Buchung der Basis auf dem Sitz durch eine andere ersetzt, "Meine" hat sie geändert. Der Sitz behält
	 * "Deren". Lebt die Buchung in einer Geschwister-Einheit ("Deren" hat sie verschoben), geht die eigene Änderung dorthin
	 * ({@link #transfer}, betroffen bei strittigen Gruppen ist dieser Rechner). Sonst hat "Deren" sie storniert, und die
	 * Stornierung gilt: Vermerk DELETE_WINS an diesen Rechner.
	 */
	private static Resolution replacedByTheirs(ConflictInput input, String bookingId, String theirsId) {
		Version theirs = input.theirs();
		Version replacement = theirsId == null ? null
				: FieldMerge.introducedBy(input.chainSinceBase(), version -> theirsId.equals(idOf(version)));
		if (replacement == null) {
			replacement = theirs;
		}
		UnitSnapshot copy = livingCopy(input, bookingId);
		if (copy != null) {
			return transfer(input, theirs.payload(), false, copy, input.mine().payload(), input.ownMachineId(),
					PolicyRules.party(replacement), input.mine().id());
		}
		Version deletion = FieldMerge.introducedBy(input.chainSinceBase(), Version::deleted);
		ConflictNote note = new ConflictNote(Kind.DELETE_WINS, input.ownMachineId(),
				PolicyRules.party(deletion != null ? deletion : replacement), List.of(), null, null, input.mine().id());
		return new Resolution(theirs.payload(), false, List.of(), note);
	}

	/**
	 * "Meine" hat die Buchung der Basis auf dem Sitz durch eine andere ersetzt, "Deren" hat sie geändert. Der Sitz behält
	 * "Meine". Lebt die Buchung in einer Kopie aus dem eigenen Speichervorgang (verschoben), geht die Änderung von "Deren"
	 * dorthin ({@link #transfer}, betroffen bei strittigen Gruppen ist der Rechner von "Deren"). Sonst hat dieser Rechner sie
	 * storniert, und die Stornierung gilt: Vermerk DELETE_WINS an den Rechner von "Deren", wenn er sie gegenüber der Basis
	 * geändert hatte.
	 */
	private static Resolution replacedByMine(ConflictInput input, String bookingId) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		UnitSnapshot copy = movedCopy(input, bookingId, mine.saveId());
		if (copy != null) {
			return transfer(input, mine.payload(), false, copy, theirs.payload(), theirs.machine(),
					PolicyRules.party(mine), theirs.id());
		}
		ConflictNote note = PolicyRules.sameContent(theirs, input.base()) ? null
				: new ConflictNote(Kind.DELETE_WINS, theirs.machine(), PolicyRules.party(mine), List.of(), null, null,
						theirs.id());
		return new Resolution(mine.payload(), false, List.of(), note);
	}

	/**
	 * Doppelverkauf: Der Sitz behält "Deren" unangetastet. "Meine" wird als neue Einheit {@code _<meine ID>} geschrieben,
	 * mit allen Kundendaten, aber "ohne Platz": {@code unassigned}, Sitz-ID, Reihe, Tisch und Sitz geleert wie bei einer
	 * neuen Buchung. Sonst ordnete {@code BookingSync} den Sitz je nach Reihenfolge der Verlierer-Buchung zu, und sobald er
	 * frei wird, setzte es sie still dorthin zurück. Der bisherige Platz steht nur im Vermerk DOUBLE_SALE.
	 * <p>
	 * Der Schlüssel {@code _<ID>} ist fest, damit eine Wiederholung nach einem Absturz dieselbe Einheit trifft. Ist sie
	 * schon gelöscht (die Buchung stand früher ohne Platz und wurde von dort zugewiesen), baut die neue Version auf der
	 * Löschung auf; eine Erstversion übersprünge die Auflösung, weil die Einheit schon Versionen hat. Lebt die Buchung
	 * schon in einer Geschwister-Einheit (auch {@code _<ID>}), entsteht keine zweite: Der Vermerk nennt diese Einheit als
	 * {@code moved}, der eigene Inhalt steht nur noch in "Meine". Ist die Einheit selbst schon {@code _<ID>}, kann die
	 * Buchung nicht weiter ausweichen: "Deren" bleibt, Vermerk UNMERGED.
	 */
	private static Resolution doubleSale(ConflictInput input, String mineId) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		UnitKey unit = input.unit();
		UnitKey moved = UnitKey.unassignedBooking(unit.eventId(), unit.presentationId(), mineId);
		if (moved.equals(unit)) {
			return PolicyRules.unmerged(input);
		}
		List<Change> extra;
		UnitSnapshot living = livingCopy(input, mineId);
		if (living != null) {
			moved = living.unit();
			extra = List.of();
		} else {
			ObjectNode withoutPlace = mine.payload().deepCopy();
			withoutPlace.put(UNASSIGNED, true);
			withoutPlace.putNull(SEAT_ID);
			withoutPlace.put(ROW_LABEL, "");
			withoutPlace.put(TABLE_NUMBER, 0);
			withoutPlace.put(SEAT_NUMBER, 0);
			Sibling existing = input.siblings().get(moved);
			List<VersionId> parents = existing != null && existing.snapshot().version() != null
					&& existing.snapshot().deleted() ? List.of(existing.snapshot().version()) : List.of();
			extra = List.of(new Change(moved, parents, withoutPlace, false));
		}

		String theirsId = idOf(theirs);
		Version introducer = theirsId == null ? null
				: FieldMerge.introducedBy(input.chainSinceBase(), version -> theirsId.equals(idOf(version)));
		ConflictNote note = new ConflictNote(Kind.DOUBLE_SALE, input.ownMachineId(),
				PolicyRules.party(introducer != null ? introducer : theirs), List.of(), moved,
				FieldMerge.slice(mine.payload(), PLACE), mine.id());
		return new Resolution(theirs.payload(), false, extra, note);
	}

	/** Die Buchungs-ID einer Version mit Inhalt; {@code null} ohne Version, bei einer Löschung oder ohne ID. */
	private static String idOf(Version version) {
		return version == null || version.deleted() ? null : text(version.payload());
	}

	/** Die Buchungs-ID im Inhalt ({@link ConflictInput#BOOKING_ID}); {@code null}, wenn sie fehlt. */
	private static String text(ObjectNode payload) {
		JsonNode value = payload == null ? null : payload.get(ConflictInput.BOOKING_ID);
		return value != null && value.isTextual() ? value.asText() : null;
	}
}
