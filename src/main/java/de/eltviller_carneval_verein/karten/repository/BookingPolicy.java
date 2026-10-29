package de.eltviller_carneval_verein.karten.repository;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ConflictInput.Sibling;
import de.eltviller_carneval_verein.karten.store.ConflictInput.Standing;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ConflictNote.FieldConflict;
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
 * die Löschung ein Verschieben, geht die Änderung an die verschobene Kopie ({@link #movedAgainstEdit}). War die eigene
 * Löschung von {@code _<ID>} ein Umzug, dessen Ziel verloren hat, bleibt die Buchung dort ({@link #strandedMove}). Sonst
 * gewinnt die Löschung ({@link PolicyRules#deleteWins}), aber nur gegen dieselbe Buchung
 * ({@link #cancelledAnotherBooking}).</li>
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
			resolution = strandedMove(input);
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
	 * <li>Auf {@code _<ID>}: "Deren" hat verschoben, das eigene Zuweisen ist am Ziel gescheitert (dort steht ein anderer
	 * Verkauf, {@link Sibling#lostMove()}): Die Verschiebung von "Deren" gilt, nichts wird gelöscht; Vermerk FIELDS mit der
	 * Gruppe "Platz" (eigenes Ziel gegen ihre Kopie, {@code dropped} ist die eigene Version am Ziel), Ruling R31b. Der
	 * Doppelverkauf am Ziel legt dann keine Zusatz-Einheit an ({@link #doubleSale}).</li>
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
			ConflictNote note = ConflictNote.ofFields(input.ownMachineId(), Party.of(theirDeletion),
					List.of(placeConflict(myCopy.payload(), theirCopy.payload())), null, myCopy.version());
			return new Resolution(theirs.payload(), true, List.of(deletionOf(myCopy)), note);
		}
		if (myCopy != null) {
			ConflictNote note = ConflictNote.deleteWins(input.ownMachineId(), Party.of(theirDeletion), myCopy.version());
			return new Resolution(theirs.payload(), true, List.of(deletionOf(myCopy)), note);
		}
		// R31b: Mein Zuweisen aus "ohne Platz" ist am Ziel gescheitert, Deren hat verschoben; Deren gilt, nichts löschen.
		// Ist die fremde Kopie seither weitergewandert (sie trägt den Speichervorgang der Löschung nicht mehr), gilt sie
		// ebenso: Der Vermerk muss bleiben, sonst ginge das Zuweisen still unter.
		UnitSnapshot foreignCopy = theirCopy != null ? theirCopy : livingCopy(input, bookingId);
		Version myLostCopy = foreignCopy == null || !isOwnUnassigned(input.unit(), bookingId) ? null
				: strandedTarget(input, bookingId);
		if (myLostCopy != null) {
			ConflictNote note = ConflictNote.ofFields(input.ownMachineId(), Party.of(theirDeletion),
					List.of(placeConflict(myLostCopy.payload(), foreignCopy.payload())), null, myLostCopy.id());
			return new Resolution(theirs.payload(), true, List.of(), note);
		}
		if (theirCopy != null) {
			ConflictNote note = ConflictNote.deleteWins(theirDeletion.machine(), Party.of(mine), theirCopy.version());
			return new Resolution(theirs.payload(), true, List.of(deletionOf(theirCopy)), note);
		}
		return cancelled;
	}

	/**
	 * Die Löschung in der Kette seit der Basis, die "Deren" eingebracht hat: Ist "Deren" die Zusammenführung eines dritten
	 * Rechners, gehört ein Verschieben zur ursprünglichen Löschung. Ohne Löschung in der Kette "Deren" selbst.
	 */
	private static Version deletionIn(ConflictInput input) {
		return PolicyRules.introducerOr(input.chainSinceBase(), Version::deleted, input.theirs());
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
		return input.siblings().values().stream().filter(sibling -> sibling.snapshot().carriesBooking(bookingId));
	}

	/** Bei mehreren (sollte nicht vorkommen) die erste nach Ordner, damit eine Wiederholung dasselbe entscheidet. */
	private static UnitSnapshot first(Stream<Sibling> siblings) {
		return siblings.map(Sibling::snapshot).min(Comparator.comparing(snapshot -> snapshot.unit().relativePath()))
				.orElse(null);
	}

	/** Der Konflikt der Gruppe "Platz" zwischen zwei Fassungen der Buchung (je die Felder der Gruppe als Kopie). */
	private static FieldConflict placeConflict(ObjectNode mine, ObjectNode theirs) {
		List<String> place = FieldGroups.booking().get(PLACE_GROUP);
		return new FieldConflict(PLACE_GROUP, FieldMerge.slice(mine, place), FieldMerge.slice(theirs, place));
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
		FieldMerge.copyFields(side, copy.payload(), FieldGroups.booking().get(PLACE_GROUP));
		Version base = input.base();
		FieldMerge.Result result = FieldMerge.merge(base == null || base.deleted() ? null : base.payload(), side,
				copy.payload(), FieldGroups.booking());
		List<Change> extra = result.merged().equals(copy.payload()) ? List.of()
				: List.of(new Change(copy.unit(), List.of(copy.version()), result.merged(), false));
		ConflictNote note = result.conflicts().isEmpty() ? null
				: ConflictNote.ofFields(affected, other, result.conflicts(), copy.unit(), dropped);
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
	 * <li>"Meine" gelöscht, "Deren" dieselbe Buchung geändert, und die Buchung lebt in einer Geschwister-Einheit: betroffen
	 * ist bei strittigen Gruppen der Rechner von "Deren". Der Speichervorgang der Kopie zählt nicht: Trägt "Deren" die
	 * Buchung hier, lebt sie anderswo nur durch dieses Rechners Verschieben, auch wenn die Kopie erst eine Auflösung schrieb
	 * (Doppelverkauf am Ziel, die Buchung lebt unter {@code _<ID>}).</li>
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
					Party.of(deletionIn(input)), mine.id());
		}
		if (!isSameBooking(input, theirs)) {
			return null;
		}
		UnitSnapshot copy = livingCopy(input, bookingId);
		if (copy == null) {
			return null;
		}
		return transfer(input, null, true, copy, theirs.payload(), theirs.machine(), Party.of(mine), theirs.id());
	}

	/**
	 * Regel 2, gescheiterter Umzug aus "ohne Platz" (Ruling R31): Die Einheit ist {@code _<ID>} der Buchung der Basis,
	 * "Meine" hat sie gelöscht, "Deren" ist dieselbe Buchung mit Inhalt, und keine Geschwister-Einheit trägt sie (sonst
	 * {@link #movedAgainstEdit}). Liegt in einer Geschwister-Einheit eine Version aus einem Speichervorgang der Strecke (dem
	 * der Löschung) mit dieser Buchung, die dort nicht gilt ({@link Sibling#lostMove()}), war die Löschung kein Storno,
	 * sondern die Quelle eines Umzugs, dessen Ziel verloren hat (dort steht ein anderer Verkauf; den Doppelverkauf meldet
	 * der Vermerk dort). Die
	 * Buchung bleibt hier mit "Deren"; die Felder, die "Meine" beim Umzug geändert hat (die Version am Ziel ohne die Gruppe
	 * "Platz"), kommen per Dreiwegevergleich gegen die Basis dazu. Strittige Gruppen: "Deren" gilt, Vermerk FIELDS an diesen
	 * Rechner.
	 * <p>
	 * So entscheiden beide Reihenfolgen gleich: Wird das Ziel zuerst aufgelöst, lebt die Buchung hier noch, und
	 * {@link #doubleSale} legt keine zweite Fassung an; wird diese Einheit zuerst aufgelöst, lebt sie danach hier. Nur für
	 * {@code _<ID>}: Von einem Sitz aus legt der Doppelverkauf am Ziel {@code _<ID>} an, und {@link #movedAgainstEdit}
	 * überträgt dorthin.
	 *
	 * @return die Auflösung, oder {@code null}, wenn es kein solcher Umzug war
	 */
	private static Resolution strandedMove(ConflictInput input) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		String bookingId = idOf(input.base());
		UnitKey unit = input.unit();
		if (bookingId == null || !mine.deleted() || theirs.deleted() || !isSameBooking(input, theirs)
				|| !isOwnUnassigned(unit, bookingId)) {
			return null;
		}
		Version target = strandedTarget(input, bookingId);
		if (target == null) {
			return null;
		}
		ObjectNode basePayload = input.base().payload();
		ObjectNode side = target.payload().deepCopy();
		FieldMerge.copyFields(side, basePayload, FieldGroups.booking().get(PLACE_GROUP));
		FieldMerge.Result result = FieldMerge.merge(basePayload, side, theirs.payload(), FieldGroups.booking());
		if (result.conflicts().isEmpty()) {
			return new Resolution(result.merged(), false, List.of(), null);
		}
		Version introducer = PolicyRules.introducerOr(input.chainSinceBase(), FieldGroups.booking(), result.conflicts(),
				theirs);
		ConflictNote note = ConflictNote.ofFields(input.ownMachineId(), Party.of(introducer), result.conflicts(), null,
				mine.id());
		return new Resolution(result.merged(), false, List.of(), note);
	}

	/** Ob die Einheit {@code _<ID>} dieser Buchung ist (die Buchung "ohne Platz"). */
	private static boolean isOwnUnassigned(UnitKey unit, String bookingId) {
		return unit.equals(UnitKey.unassignedBooking(unit.eventId(), unit.presentationId(), bookingId));
	}

	/**
	 * Das {@link Sibling#lostMove() verlorene Ziel} eines Umzugs der Buchung aus einem Speichervorgang der verlorenen
	 * Strecke ({@link ConflictInput#mineSaveIds()}, derselbe Schlüssel wie in {@link #doubleSale}) in einer
	 * Geschwister-Einheit, die die Buchung nicht trägt; bei mehreren das erste nach Ordner.
	 *
	 * @return die Version am Ziel, oder {@code null}, wenn es keine gibt
	 */
	private static Version strandedTarget(ConflictInput input, String bookingId) {
		Set<String> saveIds = input.mineSaveIds();
		return input.siblings().values().stream()
				.filter(sibling -> sibling.lostMove() != null && !sibling.lostMove().deleted()
						&& sibling.lostMove().saveId() != null && saveIds.contains(sibling.lostMove().saveId())
						&& bookingId.equals(idOf(sibling.lostMove()))
						&& !sibling.snapshot().carriesBooking(bookingId))
				.min(Comparator.comparing(sibling -> sibling.snapshot().unit().relativePath())).map(Sibling::lostMove)
				.orElse(null);
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
		Version replacement = introducerOf(input, theirsId, theirs);
		UnitSnapshot copy = livingCopy(input, bookingId);
		if (copy != null) {
			return transfer(input, theirs.payload(), false, copy, input.mine().payload(), input.ownMachineId(),
					Party.of(replacement), input.mine().id());
		}
		Version deletion = PolicyRules.introducerOr(input.chainSinceBase(), Version::deleted, replacement);
		ConflictNote note = ConflictNote.deleteWins(input.ownMachineId(), Party.of(deletion), input.mine().id());
		return new Resolution(theirs.payload(), false, List.of(), note);
	}

	/**
	 * Die Version, die die Buchung mit dieser ID in die Kette seit der Basis eingebracht hat
	 * ({@link PolicyRules#introducerOr}); ohne ID oder ohne solche Version {@code fallback}.
	 */
	private static Version introducerOf(ConflictInput input, String bookingId, Version fallback) {
		return bookingId == null ? fallback
				: PolicyRules.introducerOr(input.chainSinceBase(), version -> bookingId.equals(idOf(version)), fallback);
	}

	/**
	 * "Meine" hat die Buchung der Basis auf dem Sitz durch eine andere ersetzt, "Deren" hat sie geändert. Der Sitz behält
	 * "Meine". Lebt die Buchung in einer Geschwister-Einheit, geht die Änderung von "Deren" dorthin ({@link #transfer},
	 * betroffen bei strittigen Gruppen ist der Rechner von "Deren"). Trägt "Deren" die Buchung auf dem Sitz, kann sie
	 * anderswo nur leben, weil dieser Rechner sie verschoben hat; der Speichervorgang zählt nicht, denn "Meine" kann eine
	 * spätere Änderung der neuen Buchung sein. Sonst hat dieser Rechner sie
	 * storniert, und die Stornierung gilt: Vermerk DELETE_WINS an den Rechner von "Deren", wenn er sie gegenüber der Basis
	 * geändert hatte.
	 */
	private static Resolution replacedByMine(ConflictInput input, String bookingId) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		UnitSnapshot copy = livingCopy(input, bookingId);
		if (copy != null) {
			return transfer(input, mine.payload(), false, copy, theirs.payload(), theirs.machine(), Party.of(mine),
					theirs.id());
		}
		ConflictNote note = PolicyRules.sameContent(theirs, input.base()) ? null
				: ConflictNote.deleteWins(theirs.machine(), Party.of(mine), theirs.id());
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
	 * Löschung auf; eine Erstversion übersprünge die Auflösung, weil die Einheit schon Versionen hat. Lebt {@code _<ID>}
	 * schon, entsteht keine zweite Fassung darauf (der eigene Inhalt steht nur noch in "Meine"). Eine andere Einheit, die
	 * die Buchung noch trägt, zählt nicht: Sie lebt dort womöglich nur, weil die eigene Löschung dort verloren hat und noch
	 * nicht aufgelöst ist (verschoben, während ein anderer Rechner sie dort änderte). Deren Auflösung überträgt die Änderung
	 * dann auf {@code _<ID>} ({@link #movedAgainstEdit}); ohne {@code _<ID>} löschte sie die Buchung dort, und sie lebte
	 * nirgends mehr. Lebt {@code _<ID>} nur, weil die eigene Löschung dort verloren hat (die Buchung kam von dort hierher,
	 * Zuweisen aus "ohne Platz"), bleibt sie dort trotzdem: Die Auflösung von {@code _<ID>} erkennt den gescheiterten Umzug
	 * und löscht nicht ({@link #strandedMove}, Ruling R31); die Felder, die "Meine" dabei geändert hat, überträgt sie.
	 * <p>
	 * Hat "Meine" die Buchung von {@code _<ID>} hierher zugewiesen ({@link #assignedFrom}) und hat ein anderer Rechner sie
	 * dort ebenfalls gelöscht ({@link #deletedThereByOthers}), gibt es keine Wiederbelebung (R21 gilt nur, wenn die Löschung
	 * dort allein dieses Rechners Zuweisen war, Ruling R35), und der Sitz behält "Deren":
	 * <ul>
	 * <li>Lebt die Buchung nirgends, hat er storniert (Ruling R31a): Dieser Rechner bekommt hier statt des Doppelverkaufs den
	 * Vermerk DELETE_WINS ({@code dropped} = "Meine", zum Wiederherstellen).</li>
	 * <li>Lebt sie anderswo, hat er sie verschoben, und sein Umzug gilt (Ruling R31b). Den Vermerk FIELDS "place" schreibt
	 * die Auflösung von {@code _<ID>} ({@link #bothDeleted}), wenn die eigene Löschung dort verloren hat; hat sie dort
	 * gewonnen, gibt es auf diesem Rechner keine Auflösung von {@code _<ID>}, und der Vermerk steht hier, mit
	 * {@code moved} = Einheit der Kopie (dort lebt die Buchung, nicht auf dem Sitz des Vermerks). Hat der andere Rechner
	 * seine Löschung dann noch nicht aufgelöst und stammt die Kopie aus ihr, lebt {@code _<ID>} bis dahin wieder
	 * ({@link #movedThereAndUnresolved}): Seine Auflösung überträgt dann auf die Kopie, statt sie zu löschen.</li>
	 * </ul>
	 * Ist die Einheit selbst schon {@code _<ID>}, kann die Buchung nicht weiter ausweichen: "Deren" bleibt, Vermerk UNMERGED.
	 */
	private static Resolution doubleSale(ConflictInput input, String mineId) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		UnitKey unit = input.unit();
		UnitKey moved = UnitKey.unassignedBooking(unit.eventId(), unit.presentationId(), mineId);
		if (moved.equals(unit)) {
			return PolicyRules.unmerged(input);
		}
		Party other = Party.of(introducerOf(input, idOf(theirs), theirs));
		List<Change> extra;
		Sibling existing = input.siblings().get(moved);
		UnitSnapshot current = existing == null ? null : existing.snapshot();
		if (current != null && current.isLive()) {
			extra = List.of();
		} else if (assignedFrom(input, existing) && deletedThereByOthers(input, existing)) {
			UnitSnapshot copy = livingCopy(input, mineId);
			if (copy == null) {
				// R31a: Storno gewinnt
				ConflictNote note = ConflictNote.deleteWins(input.ownMachineId(), other, mine.id());
				return new Resolution(theirs.payload(), false, List.of(), note);
			}
			if (!ownDeletionHolds(input, existing)) {
				// R31b: Den Vermerk bekommt die eigene Auflösung von _<ID> (bothDeleted)
				return new Resolution(theirs.payload(), false, List.of(), null);
			}
			ConflictNote note = ConflictNote.ofFields(input.ownMachineId(), other,
					List.of(placeConflict(mine.payload(), copy.payload())), copy.unit(), mine.id());
			List<Change> revived = movedThereAndUnresolved(input, existing, copy)
					? List.of(withoutPlace(mine, moved, current))
					: List.of();
			return new Resolution(theirs.payload(), false, revived, note);
		} else {
			extra = List.of(withoutPlace(mine, moved, current));
		}

		ConflictNote note = ConflictNote.doubleSale(input.ownMachineId(), other, moved,
				FieldMerge.slice(mine.payload(), PLACE), mine.id());
		return new Resolution(theirs.payload(), false, extra, note);
	}

	/**
	 * "Meine" als Einheit {@code _<ID>} "ohne Platz": mit allen Kundendaten, {@code unassigned}, Sitz-ID, Reihe, Tisch und
	 * Sitz geleert wie bei einer neuen Buchung; als Nachfolger ihres aktuellen Stands, wenn sie schon einen hat.
	 */
	private static Change withoutPlace(Version mine, UnitKey moved, UnitSnapshot current) {
		ObjectNode withoutPlace = mine.payload().deepCopy();
		withoutPlace.put(UNASSIGNED, true);
		withoutPlace.putNull(SEAT_ID);
		withoutPlace.put(ROW_LABEL, "");
		withoutPlace.put(TABLE_NUMBER, 0);
		withoutPlace.put(SEAT_NUMBER, 0);
		List<VersionId> parents = current != null && current.version() != null ? List.of(current.version()) : List.of();
		return new Change(moved, parents, withoutPlace, false);
	}

	/**
	 * Ob die fremde Löschung von {@code _<ID>} noch daneben liegt (ihr Rechner hat sie noch nicht aufgelöst) und die lebende
	 * Kopie aus ihrem Speichervorgang stammt: Dann sieht seine Auflösung von {@code _<ID>} die gewonnene eigene Löschung als
	 * Storno, denn das verlorene Ziel hier kennt sie nicht, und löschte nach Regel 0 seine Kopie
	 * ({@link #bothDeleted}, verschoben gegen storniert). Lebt {@code _<ID>} wieder, überträgt sie stattdessen auf die
	 * Kopie und löscht {@code _<ID>} ({@link #movedAgainstEdit}); sein Umzug gilt (Ruling R35).
	 */
	private static boolean movedThereAndUnresolved(ConflictInput input, Sibling source, UnitSnapshot copy) {
		Sibling carrier = input.siblings().get(copy.unit());
		return carrier != null && source.deletions().stream()
				.anyMatch(deletion -> deletion.standing() == Standing.BESIDE && deletion.saveId() != null
						&& !input.mineSaveIds().contains(deletion.saveId())
						&& carrier.saveIds().contains(deletion.saveId()));
	}

	/**
	 * Ob "Meine" die Buchung von {@code _<ID>} hierher zugewiesen hat: {@code _<ID>} ist gelöscht, und in ihrem Ordner liegt
	 * eine Version aus einem Speichervorgang der verlorenen Strecke ({@link Sibling#saveIds()} gegen
	 * {@link ConflictInput#mineSaveIds()}; irgendeiner, denn nach dem Zuweisen kann dieser Rechner die Buchung hier weiter
	 * geändert haben, Ruling R35).
	 */
	private static boolean assignedFrom(ConflictInput input, Sibling source) {
		if (source == null || source.snapshot().version() == null || !source.snapshot().deleted()) {
			return false;
		}
		return source.saveIds().stream().anyMatch(input.mineSaveIds()::contains);
	}

	/**
	 * Ob ein anderer Speichervorgang als die der verlorenen Strecke die Buchung auf {@code _<ID>} gelöscht hat
	 * ({@link Sibling#deletions()}), gleich ob diese Löschung dort gilt, aufgegangen ist oder noch daneben liegt: Dann hat ein
	 * anderer Rechner storniert oder verschoben, und die Löschung dort war nicht allein dieses Rechners Zuweisen. Eine
	 * Zusammenführung, die die eigene Löschung bestätigt, zählt nicht (sie steht nicht unter den Löschungen).
	 */
	private static boolean deletedThereByOthers(ConflictInput input, Sibling source) {
		return source.deletions().stream()
				.anyMatch(deletion -> deletion.saveId() == null || !input.mineSaveIds().contains(deletion.saveId()));
	}

	/**
	 * Ob die eigene Löschung von {@code _<ID>} (aus einem Speichervorgang der Strecke) dort in der gültigen Kette steht,
	 * also gewonnen hat: Dann hat dieser Rechner dort keine verlorene Strecke, deren Auflösung ihm einen Vermerk schriebe.
	 */
	private static boolean ownDeletionHolds(ConflictInput input, Sibling source) {
		return source.deletions().stream().anyMatch(deletion -> deletion.standing() == Standing.CHAIN
				&& deletion.saveId() != null && input.mineSaveIds().contains(deletion.saveId()));
	}

	/** Die Buchungs-ID einer Version mit Inhalt; {@code null} ohne Version, bei einer Löschung oder ohne ID. */
	private static String idOf(Version version) {
		return version == null || version.deleted() ? null : ConflictInput.bookingIdOf(version.payload());
	}
}
