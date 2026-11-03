package de.eltviller_carneval_verein.karten.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ConflictInput.Sibling;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ConflictNote.FieldConflict;
import de.eltviller_carneval_verein.karten.store.ConflictNote.Party;
import de.eltviller_carneval_verein.karten.store.ConflictPolicy;
import de.eltviller_carneval_verein.karten.store.Resolution;
import de.eltviller_carneval_verein.karten.store.TrailUnit;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;
import de.eltviller_carneval_verein.karten.store.VersionMarks;

/**
 * Die Fachregel für Buchungen (Sitz-Einheit oder {@code _<ID>}). Die Regeln gelten in dieser Reihenfolge:
 * <ol start="0">
 * <li>Umzug oder Storno einer Buchung: Hat eine Seite die Buchung der Basis hier weggenommen oder die Strecke eine Buchung
 * hereingeholt, entscheidet die Umzugsspur je Buchung ({@link #moves}, KARV-93: {@link BookingTrail},
 * {@link BookingFate}). Das steht vor "Gleich": Zwei inhaltlich gleiche Fassungen können eine Buchung an verschiedene
 * Ziele geschickt haben.</li>
 * <li>Gleich ({@link PolicyRules#equal}).</li>
 * <li>Löschung gegen Änderung: Trägt "Meine" nicht die Buchung der Basis, gilt sie ({@link #saleOnCancelledSeat}). Sonst
 * gewinnt die Löschung ({@link PolicyRules#deleteWins}), aber nur gegen dieselbe Buchung
 * ({@link #cancelledAnotherBooking}).</li>
 * <li>Verschiedene Buchungen auf dem Sitz: entschieden danach, wer die Buchung der Basis trägt, siehe
 * {@link #otherBooking}.</li>
 * <li>Gleiche Buchung: Dreiwegevergleich je Feldgruppe ({@link FieldGroups#booking()}) mit der Koppelregel für Zahlung
 * und Preis ({@link #mergeBooking}).</li>
 * </ol>
 * Die Regeln 1 bis 4 erreichen Umzüge nur noch, wenn die Spur sie nicht entscheiden konnte (eine Version darauf ist
 * unlesbar). Eine Buchungs-ID lebt danach nie in zwei Einheiten zugleich. Ein unlesbares oder gesperrtes "Deren" führt nicht zu
 * UNMERGED, sondern zum Doppelverkauf (K1): Die eigene Buchung weicht "ohne Platz" aus, statt zu verschwinden; außer auf
 * einer Einheit {@code _<ID>}, dort kann sie nicht ausweichen, und es bleibt bei UNMERGED. Zustandslos; läuft auf dem
 * Hintergrund-Thread des Versionsspeichers und kennt weder Modell noch Repository, alle Eingaben sind Kopien.
 */
public final class BookingPolicy implements ConflictPolicy {

	private static final Logger LOGGER = Logger.getLogger(BookingPolicy.class.getName());

	private static final String SEAT_ID = "seatId";
	private static final String ROW_LABEL = "rowLabel";
	private static final String TABLE_NUMBER = "tableNumber";
	private static final String SEAT_NUMBER = "seatNumber";
	private static final String UNASSIGNED = "unassigned";
	/** Der Platz im Vermerk eines Doppelverkaufs, für die Anzeige im Dialog. */
	static final List<String> PLACE = List.of(SEAT_ID, ROW_LABEL, TABLE_NUMBER, SEAT_NUMBER);
	/** Die Feldgruppe "Platz". */
	static final String PLACE_GROUP = "place";
	/** Die Feldgruppe "Zahlung" der Koppelregel ({@link #mergeBooking}). */
	private static final String PAYMENT_GROUP = "payment";
	/** Die Feldgruppe "Preis" der Koppelregel; sie besteht nur aus dem gleichnamigen Feld. */
	private static final String PRICE = "price";
	private static final String PAYMENT_STATUS = "paymentStatus";
	private static final String PAID_AMOUNT = "paidAmount";
	private static final List<String> PAID_AMOUNT_ONLY = List.of(PAID_AMOUNT);
	/**
	 * Die Zahlstatus, die als bezahlt gelten; wie {@code PaymentStatus.isPaid()}, die Regel kennt das Modell nicht (ein Test
	 * gleicht beide ab).
	 */
	static final Set<String> PAID_STATUSES = Set.of("CASH", "CARD", "TRANSFER");

	@Override
	public Resolution resolve(ConflictInput given) {
		ConflictInput input = withBaseFromChain(given);
		Resolution resolution = moves(input);
		if (resolution == null) {
			resolution = PolicyRules.equal(input);
		}
		if (resolution == null) {
			resolution = saleOnCancelledSeat(input);
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
			// Regel 4 erreichen nur zwei lesbare, nicht gelöschte Fassungen derselben Buchung: PolicyRules.common griffe nie
			resolution = PolicyRules.fromMerge(input, FieldGroups.booking(), ruleFour(input));
		}
		return resolution;
	}

	/**
	 * Die Entscheidung je Buchung (KARV-93, {@link BookingFate}) für die Buchungen, deren Schicksal diese Einheit berührt:
	 * die Buchung der Basis, wenn eine Seite sie hier weggenommen hat (die Einheit ist ihre Quelle), und die Buchung, die
	 * die Strecke mit {@code movedFrom} hereingeholt hat (die Einheit ist ihr Ziel). Die Auflösung schreibt davon, was noch
	 * fehlt:
	 * <ul>
	 * <li>Die Fassung der Einheit ist der Inhalt des Ergebnisses, wenn sie der Träger ist; sonst die Buchung, die eine Seite
	 * hier neu hingesetzt hat (zuerst "Deren"); sonst eine Löschung.</li>
	 * <li>Ein anderer Träger bekommt den Inhalt als Zusatz-Änderung, wenn sein Stand ihn nicht schon hat; Träger auf der
	 * Spur, die die Buchung nicht mehr tragen dürfen, eine Löschung.</li>
	 * <li>Den Vermerk schreibt die Einheit, die das Ergebnis dafür nennt; einem Vermerk ohne Gegenseite (am Ziel eines
	 * gescheiterten Umzugs) gibt sie die Version, die den Inhalt hier eingebracht hat.</li>
	 * </ul>
	 *
	 * @return die Auflösung, oder {@code null}, wenn keine Buchung zu entscheiden ist (oder beide Seiten dieselbe Buchung
	 *         hier tragen: dann Regel 4)
	 */
	private static Resolution moves(ConflictInput input) {
		if (input.trail().isEmpty()) {
			return null;
		}
		UnitKey unit = input.unit();
		Map<String, BookingFate> fates = new LinkedHashMap<>();
		Map<String, Version> bases = new LinkedHashMap<>();
		String baseId = idOf(input.base());
		if (baseId != null) {
			BookingFate fate = fateAt(input, baseId, unit, input.base());
			if (fate != null) {
				fates.put(baseId, fate);
				bases.put(baseId, input.base());
			}
		}
		for (Version version : input.stretch()) {
			UnitKey source = version.marks().movedFrom();
			String id = idOf(version);
			if (source != null && id != null && !fates.containsKey(id)) {
				VersionId sourceBase = BookingTrail.sourceBase(input, source, version);
				TrailUnit at = input.trail().get(source);
				Version base = at == null || sourceBase == null ? null : at.version(sourceBase);
				BookingFate fate = base == null ? null : fateAt(input, id, source, base);
				if (fate != null) {
					fates.put(id, fate);
					bases.put(id, base);
				}
			}
		}
		if (fates.isEmpty()) {
			return null;
		}
		ObjectNode payload = null;
		ConflictNote note = null;
		for (Map.Entry<String, BookingFate> entry : fates.entrySet()) {
			BookingFate fate = entry.getValue();
			if (!unit.equals(fate.carrier())) {
				continue;
			}
			if (fate.content() != null) {
				payload = fate.content();
			} else {
				// E6 hier: beide Umzüge kamen hier an; ihre Fassungen werden wie Regel 4 zusammengeführt
				FieldMerge.Result merged = sameArrival(input, entry.getKey(), bases.get(entry.getKey()));
				payload = merged.merged();
				if (!merged.conflicts().isEmpty()) {
					note = ConflictNote.ofFields(input.ownMachineId(), Party.of(input.theirs()), merged.conflicts(), null,
							input.mine().id());
				}
			}
		}
		if (payload == null) {
			payload = newcomer(input, fates.keySet());
		}
		List<Change> extra = new ArrayList<>();
		for (Map.Entry<String, BookingFate> entry : fates.entrySet()) {
			BookingFate fate = entry.getValue();
			if (fate.carrier() != null && !unit.equals(fate.carrier()) && fate.content() != null) {
				Change content = contentChange(input, fate.carrier(), fate.content());
				if (content != null) {
					if (fate.carrier().isUnassigned() && !fate.carrier().equals(fate.source())) {
						// geparkt (E9 ohne freie Quelle, E10, E11): das Parken nennt seine Quelle (KARV-98)
						content = content.withMarks(VersionMarks.NONE.withMovedFrom(fate.source()));
					}
					extra.add(content);
				}
			}
			for (UnitKey deletion : fate.deletions()) {
				Sibling sibling = input.siblings().get(deletion);
				if (!unit.equals(deletion) && sibling != null && sibling.snapshot().carriesBooking(entry.getKey())) {
					extra.add(deletionOf(sibling.snapshot()));
				}
			}
			for (BookingFate.Placed placed : fate.notes()) {
				if (unit.equals(placed.writtenAt())) {
					if (note == null) {
						note = withOther(input, placed.note());
					} else {
						LOGGER.warning("Zwei Vermerke an " + unit.relativePath() + "; nur der erste wird geschrieben.");
					}
				}
			}
		}
		return new Resolution(payload, payload == null, extra, note);
	}

	/**
	 * Der Dreiwegevergleich von Regel 4. Baut die Absicht einer Seite auf einer Auflösung auf (KARV-98, Spec Abschnitt 1b),
	 * rechnet er gegen deren Inhalt; sonst gälte eine Änderung, die die Seite schon vor der Auflösung machte und danach
	 * weiterführte, als Konflikt gegen sich selbst, und das Ergebnis hinge davon ab, wer auflöst. Die andere Seite gilt dann
	 * in einer Feldgruppe nur als geändert, wenn sie sich von ihrer Basis und von der Auflösung unterscheidet: Was ihr fehlt,
	 * weil sie älter ist als die Auflösung, hat sie nicht zurückgesetzt. Bauen beide auf verschiedenen Auflösungen auf,
	 * oder keine, bleibt es beim Vergleich gegen die Basis der Eingabe.
	 */
	private static FieldMerge.Result ruleFour(ConflictInput input) {
		ObjectNode base = PolicyRules.basePayload(input);
		ObjectNode mine = input.mine().payload();
		ObjectNode theirs = input.theirs().payload();
		String bookingId = idOf(input.base());
		if (bookingId == null || input.trail().isEmpty()) {
			return mergeBooking(base, mine, theirs);
		}
		Version own = BookingTrail.of(input, bookingId, input.unit(), input.base().id(), true).after();
		Version foreign = BookingTrail.of(input, bookingId, input.unit(), input.base().id(), false).after();
		Version after = own != null ? own : foreign;
		if (after == null || after.deleted() || own != null && foreign != null && !own.id().equals(foreign.id())) {
			return mergeBooking(base, mine, theirs);
		}
		ObjectNode resolved = after.payload();
		if (own == null) {
			mine = unchangedTaken(mine, base, resolved);
		} else if (foreign == null) {
			theirs = unchangedTaken(theirs, base, resolved);
		}
		return mergeBooking(resolved, mine, theirs);
	}

	/** Eine Kopie von {@code side}, in der jede Feldgruppe, die sie gegenüber {@code base} nicht änderte, die von {@code resolved} ist. */
	private static ObjectNode unchangedTaken(ObjectNode side, ObjectNode base, ObjectNode resolved) {
		return FieldMerge.merge(base, resolved, side, FieldGroups.booking()).merged();
	}

	/**
	 * Beide Seiten haben die Buchung hierher umgezogen (E6): Dreiwegevergleich von "Meine" und "Deren" gegen die Basis an
	 * der Quelle, deren Platz als der von "Deren" gilt (der Umzug selbst ist kein Unterschied). Trägt eine Seite die Buchung
	 * hier nicht, gilt "Deren".
	 */
	private static FieldMerge.Result sameArrival(ConflictInput input, String bookingId, Version base) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		if (!bookingId.equals(idOf(mine)) || !bookingId.equals(idOf(theirs))) {
			return new FieldMerge.Result(theirs.payload(), List.of());
		}
		return mergeBooking(withPlaceOf(base.payload(), theirs.payload()), mine.payload(), theirs.payload());
	}

	/** Die Entscheidung für eine Buchung an ihrer Quelle, oder {@code null}, wenn keine Seite sie weggenommen hat. */
	private static BookingFate fateAt(ConflictInput input, String bookingId, UnitKey source, Version base) {
		BookingTrail own = BookingTrail.of(input, bookingId, source, base.id(), true);
		BookingTrail foreign = BookingTrail.of(input, bookingId, source, base.id(), false);
		if (!removed(own) && !removed(foreign)) {
			return null;
		}
		Boolean ownWins = BookingTrail.ownWinsAt(input, source, base.id());
		return BookingFate.decide(bookingId, source, base, own, foreign, ownWins, input.ownMachineId(),
				BookingTrail.freeFor(input, source, bookingId), parkedUnchanged(input, bookingId, source, base));
	}

	/**
	 * Ob eine Auflösung die Buchung in ihrer Einheit {@code _<ID>} geparkt und seitdem niemand sie geändert hat (E13,
	 * KARV-124): Die Einheit lebt mit der Fassung der Basis an der Quelle, bis auf die Gruppe "Platz". Nicht, wenn die
	 * Quelle selbst {@code _<ID>} ist. Eine geänderte Kopie zählt nicht: Die Änderung kam nach dem Parken.
	 */
	private static boolean parkedUnchanged(ConflictInput input, String bookingId, UnitKey source, Version base) {
		UnitKey parked = BookingTrail.parkedUnit(source, bookingId);
		Sibling sibling = input.siblings().get(parked);
		return !parked.equals(source) && sibling != null && sibling.snapshot().carriesBooking(bookingId)
				&& sameExceptPlace(sibling.snapshot().payload(), base.payload());
	}

	/** Ob die Fassung bis auf die Gruppe "Platz" die der Basis ist. */
	static boolean sameExceptPlace(ObjectNode payload, ObjectNode base) {
		return withPlaceOf(payload, base).equals(base);
	}

	/** Eine Kopie von {@code payload} mit der Gruppe "Platz" von {@code placeFrom}. */
	private static ObjectNode withPlaceOf(ObjectNode payload, ObjectNode placeFrom) {
		ObjectNode copy = payload.deepCopy();
		FieldMerge.copyFields(copy, placeFrom, FieldGroups.booking().get(PLACE_GROUP));
		return copy;
	}

	private static boolean removed(BookingTrail trail) {
		return trail.kind() == BookingTrail.Kind.CANCELLED || trail.kind() == BookingTrail.Kind.ARRIVED
				|| trail.kind() == BookingTrail.Kind.FAILED;
	}

	/**
	 * Was eine Seite auf die Einheit neu hingesetzt hat, ohne die entschiedenen Buchungen: zuerst "Deren", dann "Meine";
	 * {@code null}, wenn keine (die Einheit wird gelöscht).
	 */
	private static ObjectNode newcomer(ConflictInput input, Set<String> decided) {
		for (Version side : List.of(input.theirs(), input.mine())) {
			String id = idOf(side);
			if (id != null && !decided.contains(id)) {
				return side.payload();
			}
		}
		return null;
	}

	/**
	 * Der Inhalt für einen anderen Träger auf seinem aktuellen Stand (wiederbelebt, wenn er gelöscht ist; als erste
	 * Version, wenn es ihn nicht gibt); {@code null}, wenn sein Stand den Inhalt schon hat.
	 */
	private static Change contentChange(ConflictInput input, UnitKey carrier, ObjectNode content) {
		Sibling sibling = input.siblings().get(carrier);
		UnitSnapshot current = sibling == null ? null : sibling.snapshot();
		if (current != null && current.isLive() && content.equals(current.payload())) {
			return null;
		}
		List<VersionId> parents = current != null && current.version() != null ? List.of(current.version())
				: List.of();
		return new Change(carrier, parents, content, false);
	}

	/**
	 * Ein Vermerk ohne Gegenseite (gescheiterter Umzug, geschrieben an seinem Ziel) bekommt die Version, die den Inhalt von
	 * "Deren" hier eingebracht hat.
	 */
	private static ConflictNote withOther(ConflictInput input, ConflictNote note) {
		if (note.other() != null) {
			return note;
		}
		Party other = Party.of(introducerOf(input, idOf(input.theirs()), input.theirs()));
		return new ConflictNote(note.kind(), note.affected(), other, note.fields(), note.moved(), note.place(),
				note.dropped());
	}

	/**
	 * Hat "Meine" keine Basis, ist "Deren" eine Löschung und trägt ein Glied der gültigen Kette davor dieselbe Buchung (ID)
	 * wie "Meine", ist "Meine" kein neuer Verkauf, sondern eine Änderung der stornierten Buchung (KARV-118): Ein neuer
	 * Verkauf bekommt immer eine neue Kennung. Der Rechner kannte die Buchung nur abgeleitet, ohne ihre Version, etwa
	 * versetzt von einem fremden Wechsel der Bestuhlung, und sein Speichern legte eine Erstfassung an. Dann gilt das älteste
	 * solche Glied als Basis, die Kette beginnt nach ihm, und die Löschung gewinnt mit Vermerk; sonst gälte "Meine" als
	 * neuer Verkauf ({@link #saleOnCancelledSeat}), und das Storno ginge still verloren.
	 * <p>
	 * Gegen eine lebende Erstfassung derselben Buchung gilt das nicht: Welche Seite dort etwas geändert hat, lässt sich ohne
	 * gemeinsame Basis nicht sagen (beide setzen etwa dieselbe Buchung nach einem Hallenwechsel um), es bleibt beim
	 * Feldkonflikt. Eine unlesbare "Meine" bleibt bei {@link #saleOnCancelledSeat} (UNMERGED): Wiederherstellen ließe sie
	 * sich ohnehin nicht.
	 */
	private static ConflictInput withBaseFromChain(ConflictInput input) {
		String bookingId = idOf(input.mine());
		if (input.base() != null || !input.theirs().deleted() || bookingId == null) {
			return input;
		}
		List<Version> chain = input.chainSinceBase();
		for (int i = 0; i < chain.size(); i++) {
			if (bookingId.equals(idOf(chain.get(i)))) {
				// erst bei einem Treffer lesen: ohne ihn bleibt es ohnehin bei saleOnCancelledSeat
				if (!PolicyRules.isReadable(input, input.mine())) {
					return input;
				}
				return new ConflictInput(input.unit(), chain.get(i), input.mine(), input.theirs(),
						chain.subList(i + 1, chain.size()), input.ownMachineId(), input.siblings(), input.stretch(),
						input.trail());
			}
		}
		return input;
	}

	/**
	 * Der Dreiwegevergleich je Feldgruppe ({@link FieldMerge#merge}) mit der Koppelregel für Zahlung und Preis: Hat die
	 * eine Seite gegenüber der Basis nur die Gruppe "Zahlung" geändert und dabei zum eigenen Preis kassiert
	 * ({@link #paidAtOwnPrice}), die andere nur die Gruppe "Preis", ist das Ergebnis bezahlt und weicht sein gezahlter
	 * Betrag vom Preis ab (ein leerer Preis, also der Standardpreis der Vorstellung, zählt als abweichend: die Einheit kennt
	 * ihn nicht), wird die Gruppe "Preis" strittig wie ein
	 * gewöhnlicher Feldkonflikt: Es gilt der Preis von "Deren", die Gruppe kommt mit beiden Ständen zu den Konflikten. Die
	 * Zahlung bleibt so in jedem Fall stehen. Ist "Preis" schon strittig, oder hat eine Seite beide Gruppen geändert, bleibt
	 * es beim gewöhnlichen Vergleich; ein älterer Stand ohne gezahlten Betrag kennt keine Abweichung.
	 *
	 * @param base der Inhalt der Basis; {@code null} gilt als leeres Objekt
	 */
	static FieldMerge.Result mergeBooking(ObjectNode base, ObjectNode mine, ObjectNode theirs) {
		Map<String, List<String>> groups = FieldGroups.booking();
		FieldMerge.Result result = FieldMerge.merge(base, mine, theirs, groups);
		if (result.conflicts().stream().anyMatch(conflict -> conflict.group().equals(PRICE))) {
			return result;
		}
		ObjectNode before = FieldMerge.orEmpty(base);
		List<String> payment = groups.get(PAYMENT_GROUP);
		List<String> price = groups.get(PRICE);
		boolean minePays = !FieldMerge.same(mine, before, payment);
		boolean minePrices = !FieldMerge.same(mine, before, price);
		boolean theirsPay = !FieldMerge.same(theirs, before, payment);
		boolean theirsPrice = !FieldMerge.same(theirs, before, price);
		ObjectNode payer;
		if (minePays && !minePrices && theirsPrice && !theirsPay) {
			payer = mine;
		} else if (theirsPay && !theirsPrice && minePrices && !minePays) {
			payer = theirs;
		} else {
			return result;
		}
		ObjectNode merged = result.merged();
		if (!paidAtOwnPrice(payer, before) || !isPaid(merged) || !amountDiffersFromPrice(merged)) {
			return result;
		}
		FieldMerge.copyFields(merged, theirs, price); // merged ist schon eine eigene Kopie
		List<FieldConflict> conflicts = new ArrayList<>(result.conflicts());
		conflicts.add(new FieldConflict(PRICE, FieldMerge.slice(mine, price), FieldMerge.slice(theirs, price)));
		return new FieldMerge.Result(merged, List.copyOf(conflicts));
	}

	/**
	 * Ob die Zahlungsseite zum eigenen Preis kassiert hat: Sie hat den gezahlten Betrag gegenüber der Basis gesetzt, und er
	 * passte bei ihr zum Preis (ein leerer Preis zählt als passend, die Einheit kennt den Standardpreis nicht). Ein bloßer
	 * Wechsel der Zahlart oder ein eigener Betrag (Rabatt) erzeugt die Abweichung nicht erst beim Zusammenführen.
	 */
	private static boolean paidAtOwnPrice(ObjectNode payer, ObjectNode base) {
		if (FieldMerge.same(payer, base, PAID_AMOUNT_ONLY)) {
			return false;
		}
		Long amount = cents(payer, PAID_AMOUNT);
		Long price = cents(payer, PRICE);
		return amount == null || price == null || price.equals(amount);
	}

	/** Ob der Zahlstatus eine bezahlte Zahlart ist; ein unbekannter Wert (etwa aus einer neueren App) zählt nicht. */
	private static boolean isPaid(ObjectNode booking) {
		JsonNode status = booking.get(PAYMENT_STATUS);
		return status != null && status.isTextual() && PAID_STATUSES.contains(status.asText());
	}

	/** Ob ein gezahlter Betrag eingetragen ist und vom Preis abweicht; ein leerer Preis zählt als abweichend. */
	private static boolean amountDiffersFromPrice(ObjectNode booking) {
		Long amount = cents(booking, PAID_AMOUNT);
		return amount != null && !amount.equals(cents(booking, PRICE));
	}

	/** Ein Betrag in Cent; {@code null}, wenn das Feld fehlt, leer oder keine Zahl ist. */
	private static Long cents(ObjectNode booking, String field) {
		JsonNode value = booking.get(field);
		return value != null && value.isNumber() ? value.asLong() : null;
	}

	/**
	 * Trägt die Felder von {@code change} per Dreiwegevergleich gegen {@code base} auf {@code keeper} über: Die Gruppe
	 * "Platz" bleibt die von {@code keeper}, bei strittigen Gruppen gilt {@code keeper} ({@link #mergeBooking}).
	 *
	 * @param base der Inhalt der Basis; {@code null} gilt als leeres Objekt
	 */
	static FieldMerge.Result carryOnto(ObjectNode base, ObjectNode change, ObjectNode keeper) {
		return mergeBooking(base, withPlaceOf(change, keeper), keeper);
	}

	/** Der Konflikt der Gruppe "Platz" zwischen zwei Fassungen der Buchung (je die Felder der Gruppe als Kopie). */
	static FieldConflict placeConflict(ObjectNode mine, ObjectNode theirs) {
		List<String> place = FieldGroups.booking().get(PLACE_GROUP);
		return new FieldConflict(PLACE_GROUP, FieldMerge.slice(mine, place), FieldMerge.slice(theirs, place));
	}

	/** Die Löschung einer Geschwister-Einheit auf ihrem aktuellen Stand. */
	private static Change deletionOf(UnitSnapshot sibling) {
		return new Change(sibling.unit(), List.of(sibling.version()), null, true);
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
	 * "Deren" hat die Buchung der Basis auf dem Sitz durch eine andere ersetzt, "Meine" hat sie geändert, und die
	 * Umzugsspur hat das nicht entschieden ({@link #moves}): Eine Auflösung hat die Buchung weggesetzt (Doppelverkauf, dann
	 * speicherte dieser Rechner auf seinem alten Stand weiter), oder eine Version auf der Spur ist unlesbar. Der Sitz behält
	 * "Deren". Lebt die Buchung in einer Geschwister-Einheit, geht die eigene Änderung dorthin ({@link #transfer}, betroffen
	 * bei strittigen Gruppen ist dieser Rechner); sonst gilt die Stornierung: Vermerk DELETE_WINS an diesen Rechner, die
	 * eigene Fassung bleibt zum Wiederherstellen.
	 */
	private static Resolution replacedByTheirs(ConflictInput input, String bookingId, String theirsId) {
		Version theirs = input.theirs();
		Version replacement = introducerOf(input, theirsId, theirs);
		UnitSnapshot copy = carrierOf(input, bookingId);
		if (copy != null) {
			return transfer(input, theirs.payload(), copy, input.mine().payload(), input.ownMachineId(),
					Party.of(replacement), input.mine().id());
		}
		Version deletion = PolicyRules.introducerOr(input.chainSinceBase(), Version::deleted, replacement);
		ConflictNote note = ConflictNote.deleteWins(input.ownMachineId(), Party.of(deletion), input.mine().id());
		return new Resolution(theirs.payload(), false, List.of(), note);
	}

	/**
	 * Eine Geschwister-Einheit, deren Stand die Buchung trägt (bei mehreren, was nicht vorkommen sollte, die erste nach
	 * Pfad); {@code null}, wenn keine.
	 */
	private static UnitSnapshot carrierOf(ConflictInput input, String bookingId) {
		UnitSnapshot found = null;
		for (Sibling sibling : input.siblings().values()) {
			UnitSnapshot snapshot = sibling.snapshot();
			if (snapshot.carriesBooking(bookingId)
					&& (found == null || snapshot.unit().relativePath().compareTo(found.unit().relativePath()) < 0)) {
				found = snapshot;
			}
		}
		return found;
	}

	/**
	 * Überträgt eine Änderung der Buchung auf ihre Kopie in einer anderen Einheit, statt sie zu verwerfen: Dreiwegevergleich
	 * gegen die Basis mit {@code change} auf der einen und der Kopie auf der anderen Seite; die Gruppe "Platz" bleibt die der
	 * Kopie, bei strittigen Gruppen gilt die Kopie. Ergibt das einen anderen Inhalt, bekommt die Kopie eine neue Version auf
	 * ihrem aktuellen Stand. Strittige Gruppen kommen in einen Vermerk FIELDS mit {@code moved} = Einheit der Kopie.
	 *
	 * @param payload  die Fassung der Einheit selbst
	 * @param copy     die Kopie
	 * @param change   der Inhalt, dessen Änderung übertragen wird
	 * @param affected der Rechner, dessen Änderung bei strittigen Gruppen weicht
	 * @param other    die Gegenseite des Vermerks
	 * @param dropped  die Version mit der weichenden Änderung
	 */
	private static Resolution transfer(ConflictInput input, ObjectNode payload, UnitSnapshot copy, ObjectNode change,
			String affected, Party other, VersionId dropped) {
		FieldMerge.Result result = carryOnto(PolicyRules.basePayload(input), change, copy.payload());
		List<Change> extra = result.merged().equals(copy.payload()) ? List.of()
				: List.of(new Change(copy.unit(), List.of(copy.version()), result.merged(), false));
		ConflictNote note = result.conflicts().isEmpty() ? null
				: ConflictNote.ofFields(affected, other, result.conflicts(), copy.unit(), dropped);
		return new Resolution(payload, false, extra, note);
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
	 * "Meine" hat die Buchung der Basis auf dem Sitz durch eine andere ersetzt, "Deren" hat sie geändert, und die
	 * Umzugsspur hat das nicht entschieden ({@link #moves}). Der Sitz behält "Meine". Lebt die Buchung in einer
	 * Geschwister-Einheit, geht die Änderung von "Deren" dorthin ({@link #transfer}, betroffen bei strittigen Gruppen ist der
	 * Rechner von "Deren"); sonst gilt die Stornierung: Vermerk DELETE_WINS an den Rechner von "Deren", wenn er sie
	 * gegenüber der Basis geändert hatte.
	 */
	private static Resolution replacedByMine(ConflictInput input, String bookingId) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		UnitSnapshot copy = carrierOf(input, bookingId);
		if (copy != null) {
			return transfer(input, mine.payload(), copy, theirs.payload(), theirs.machine(), Party.of(mine), theirs.id());
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
	 * schon, entsteht keine zweite Fassung darauf (der eigene Inhalt steht nur noch in "Meine"). Ein Umzug der Buchung
	 * hierher kommt hier nicht an: Ihn entscheidet {@link #moves} (KARV-93); hierher gelangen nur zwei verschiedene neue
	 * Verkäufe auf demselben Sitz.
	 * <p>
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
		Sibling existing = input.siblings().get(moved);
		UnitSnapshot current = existing == null ? null : existing.snapshot();
		// das Parken nennt seine Quelle (KARV-98): Eine spätere Auflösung an der Quelle findet den Parkweg
		List<Change> extra = current != null && current.isLive() ? List.of()
				: List.of(withoutPlace(mine, moved, current).withMarks(VersionMarks.NONE.withMovedFrom(unit)));

		ConflictNote note = ConflictNote.doubleSale(input.ownMachineId(), other, moved,
				FieldMerge.slice(mine.payload(), PLACE), mine.id());
		return new Resolution(theirs.payload(), false, extra, note);
	}

	/**
	 * "Meine" als Einheit {@code _<ID>} "ohne Platz": mit allen Kundendaten, {@code unassigned}, Sitz-ID, Reihe, Tisch und
	 * Sitz geleert wie bei einer neuen Buchung; als Nachfolger ihres aktuellen Stands, wenn sie schon einen hat.
	 */
	private static Change withoutPlace(Version mine, UnitKey moved, UnitSnapshot current) {
		List<VersionId> parents = current != null && current.version() != null ? List.of(current.version()) : List.of();
		return new Change(moved, parents, withoutPlace(mine.payload()), false);
	}

	/** Eine Kopie der Buchung "ohne Platz": {@code unassigned}, Sitz-ID, Reihe, Tisch und Sitz geleert. */
	static ObjectNode withoutPlace(ObjectNode booking) {
		ObjectNode withoutPlace = booking.deepCopy();
		withoutPlace.put(UNASSIGNED, true);
		withoutPlace.putNull(SEAT_ID);
		withoutPlace.put(ROW_LABEL, "");
		withoutPlace.put(TABLE_NUMBER, 0);
		withoutPlace.put(SEAT_NUMBER, 0);
		return withoutPlace;
	}

	/** Die Buchungs-ID einer Version mit Inhalt; {@code null} ohne Version, bei einer Löschung oder ohne ID. */
	static String idOf(Version version) {
		return version == null || version.deleted() ? null : ConflictInput.bookingIdOf(version.payload());
	}
}
