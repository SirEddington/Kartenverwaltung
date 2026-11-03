package de.eltviller_carneval_verein.karten.repository;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.repository.BookingTrail.Kind;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ConflictNote.FieldConflict;
import de.eltviller_carneval_verein.karten.store.ConflictNote.Party;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;

/**
 * Was aus einer Buchung wird, wenn zwei Seiten an ihrer Quelle auseinanderliefen (KARV-93, Entscheidungstabelle E1–E12
 * der Spec, dazu E13 aus KARV-124). Entschieden wird nur aus den Absichten ({@link BookingTrail}), nie aus dem, was
 * Auflösungen schon schrieben: Jeder Rechner kommt in jeder Reihenfolge zum selben Ergebnis. Die Tabelle ist symmetrisch.
 * Einzige Ausnahme ist E13: Ob eine Auflösung die Buchung "ohne Platz" geparkt hat, sagt nur deren Einheit
 * {@code _<ID>}; dort hängt das Ergebnis davon ab, ob die geparkte Version schon eingetroffen oder schon geändert ist.
 *
 * @param carrier   die Einheit, in der die Buchung danach lebt; {@code null}: nirgends
 * @param content   ihr Inhalt dort; {@code null}: der Inhalt dort bleibt, wie er ist (E6)
 * @param deletions Träger auf der Spur, die die Buchung danach nicht mehr tragen
 * @param notes     die Vermerke, je mit der Einheit, deren Auflösung ihn schreibt; ein Vermerk ohne Gegenseite
 *                  ({@code other} = {@code null}) bekommt sie dort von der Anwendung (der Gewinner am Ziel)
 */
record BookingFate(UnitKey carrier, ObjectNode content, List<UnitKey> deletions, List<Placed> notes) {

	/** Ein Vermerk und die Einheit, deren Auflösung ihn schreibt. */
	record Placed(ConflictNote note, UnitKey writtenAt) {
	}

	BookingFate {
		deletions = List.copyOf(deletions);
		notes = List.copyOf(notes);
	}

	/**
	 * Entscheidet nach der Tabelle; {@code null}, wenn es nichts zu entscheiden gibt (beide behalten: Regel 4; eine Seite
	 * unberührt, außer E11 und E13).
	 *
	 * @param bookingId     die Buchung
	 * @param source        ihre Quelle
	 * @param base          die Basis an der Quelle (trägt die Buchung)
	 * @param own           die Absicht dieses Rechners
	 * @param foreign       die Absicht der anderen
	 * @param ownWinsSource ob an der Quelle die Version dieses Rechners gilt
	 * @param ownMachineId  die ID dieses Rechners
	 * @param sourceFree    ob die Quelle die Buchung wieder aufnehmen kann ({@link BookingTrail#freeFor}); sonst gilt dort
	 *                      inzwischen eine andere Buchung, und E9 kann sie nicht dorthin zurücklegen
	 * @param parked        ob eine Auflösung die Buchung in ihrer Einheit {@code _<ID>} unverändert geparkt hat (E13)
	 */
	static BookingFate decide(String bookingId, UnitKey source, Version base, BookingTrail own, BookingTrail foreign,
			boolean ownWinsSource, String ownMachineId, boolean sourceFree, boolean parked) {
		Side mine = new Side(own, ownMachineId);
		Side theirs = new Side(foreign, machineOf(foreign));
		Decider decider = new Decider(bookingId, source, base, sourceFree, parked);
		return decider.decide(mine, theirs, ownWinsSource);
	}

	/** Die Version, die für eine Seite steht: ihr Wegfall, sonst ihre jüngste; {@code null} bei einer unberührten. */
	private static Version representative(BookingTrail trail) {
		return trail.removal() != null ? trail.removal() : trail.last();
	}

	private static String machineOf(BookingTrail trail) {
		Version version = representative(trail);
		return version == null ? null : version.machine();
	}

	/** Eine Seite: ihre Absicht und ihr Rechner. */
	private record Side(BookingTrail trail, String machine) {

		Kind kind() {
			return trail.kind();
		}

		/** Die Version, die für die Gegenseite eines Vermerks steht: der Wegfall, sonst die jüngste. */
		Party party() {
			return Party.of(representative(trail));
		}

		/** Die weichende Version: die jüngste eigene mit der Buchung, sonst der Wegfall. */
		VersionId dropped() {
			return trail.mine() != null ? trail.mine().id() : trail.removal().id();
		}
	}

	/** Die Entscheidung für eine Buchung an ihrer Quelle. */
	private record Decider(String bookingId, UnitKey source, Version base, boolean sourceFree, boolean parked) {

		BookingFate decide(Side own, Side foreign, boolean ownWinsSource) {
			Kind a = own.kind();
			Kind b = foreign.kind();
			if (a == Kind.UNTOUCHED || b == Kind.UNTOUCHED) {
				Side touched = a == Kind.UNTOUCHED ? foreign : own;
				return switch (touched.kind()) {
				case FAILED -> aloneFailed(touched);
				case CANCELLED, ARRIVED -> againstParked(touched);
				default -> null;
				};
			}
			if (a == Kind.KEPT && b == Kind.KEPT) {
				return null;
			}
			if (a == Kind.CANCELLED || b == Kind.CANCELLED) {
				return a == Kind.CANCELLED ? cancelled(own, foreign) : cancelled(foreign, own);
			}
			if (a == Kind.ARRIVED && b == Kind.ARRIVED) {
				return bothArrived(own, foreign, ownWinsSource);
			}
			if (a == Kind.ARRIVED || b == Kind.ARRIVED) {
				return a == Kind.ARRIVED ? arrivedAgainst(own, foreign) : arrivedAgainst(foreign, own);
			}
			if (a == Kind.FAILED && b == Kind.FAILED) {
				return bothFailed(own, foreign, ownWinsSource);
			}
			return a == Kind.FAILED ? failedAgainstKept(own, foreign) : failedAgainstKept(foreign, own);
		}

		/** E1–E4: Storno gewinnt gegen alles; ein angekommener Träger wird gelöscht. */
		private BookingFate cancelled(Side cancel, Side other) {
			List<UnitKey> deletions = new ArrayList<>();
			List<Placed> notes = new ArrayList<>();
			switch (other.kind()) {
			case CANCELLED -> {
			}
			case KEPT -> {
				if (!PolicyRules.sameContent(other.trail().last(), base)) {
					notes.add(atSource(ConflictNote.deleteWins(other.machine(), cancel.party(), other.dropped())));
				}
			}
			case ARRIVED -> {
				deletions.add(other.trail().end());
				notes.add(atSource(ConflictNote.deleteWins(other.machine(), cancel.party(), other.trail().end(),
						other.dropped())));
			}
			// die weichende Fassung liegt am gescheiterten Ziel: dort sucht und schreibt "Buchung wiederherstellen"
			case FAILED -> notes.add(atSource(ConflictNote.deleteWins(other.machine(), cancel.party(),
					other.trail().last() == null ? null : other.trail().end(), other.dropped())));
			default -> throw new IllegalStateException(other.kind().name());
			}
			return new BookingFate(null, null, deletions, notes);
		}

		/** E6, E7: beide angekommen; am gleichen Ende nichts zu tun, sonst gilt der Umzug des Gewinners an der Quelle. */
		private BookingFate bothArrived(Side own, Side foreign, boolean ownWinsSource) {
			if (own.trail().end().equals(foreign.trail().end())) {
				return new BookingFate(own.trail().end(), null, List.of(), List.of());
			}
			Side winner = ownWinsSource ? own : foreign;
			Side loser = ownWinsSource ? foreign : own;
			return toCopy(winner, loser, List.of(loser.trail().end()), true);
		}

		/** E5, E8: Ein angekommener Umzug gilt gegen Behalten und gegen einen gescheiterten Umzug. */
		private BookingFate arrivedAgainst(Side arrived, Side other) {
			return toCopy(arrived, other, List.of(), other.kind() == Kind.FAILED);
		}

		/**
		 * Die Buchung lebt am Ende des Umzugs von {@code winner}; die Felder von {@code other} kommen per Dreiwegevergleich
		 * dazu (Gruppe "Platz" bleibt die der Kopie, strittig gilt die Kopie). Mit {@code place} ist "Platz" selbst strittig;
		 * der Vermerk nennt dann wie bisher (Ruling R31b) keine Einheit in {@code moved}, der Dialog folgt der Buchung über
		 * ihre Kennung. Ohne "Platz" nennt er den Träger (Ruling R26).
		 */
		private BookingFate toCopy(Side winner, Side other, List<UnitKey> deletions, boolean place) {
			UnitKey carrier = winner.trail().end();
			ObjectNode copy = fieldsOf(winner);
			ObjectNode fields = fieldsOf(other);
			FieldMerge.Result merged = carry(fields, copy);
			List<FieldConflict> conflicts = new ArrayList<>();
			if (place) {
				conflicts.add(BookingPolicy.placeConflict(fields, copy));
			}
			conflicts.addAll(merged.conflicts());
			List<Placed> notes = conflicts.isEmpty() ? List.of()
					: List.of(atSource(ConflictNote.ofFields(other.machine(), winner.party(), conflicts,
							place ? null : carrier, other.dropped())));
			return new BookingFate(carrier, merged.merged(), deletions, notes);
		}

		/**
		 * E9: gescheiterter Umzug gegen Behalten. Die Buchung bleibt an der Quelle, wenn sie frei ist (der Umziehende hat
		 * dort keine andere Buchung hinterlassen), sonst "ohne Platz"; die Felder des Umziehenden kommen dazu, strittig
		 * gilt die behaltene Fassung. Der Vermerk ist ohne Platz und ohne strittige Gruppen der Doppelverkauf ("Platz
		 * zuweisen"), sonst ein Feldkonflikt mit "Platz" und den strittigen Gruppen.
		 */
		private BookingFate failedAgainstKept(Side failed, Side kept) {
			boolean free = failed.trail().removal().deleted() && sourceFree;
			UnitKey carrier = free ? source : unassigned();
			ObjectNode keptPayload = kept.trail().last().payload();
			FieldMerge.Result merged = carry(fieldsOf(failed), keptPayload);
			ObjectNode content = carrier.isUnassigned() ? BookingPolicy.withoutPlace(merged.merged()) : merged.merged();
			ConflictNote note;
			if (carrier.isUnassigned() && merged.conflicts().isEmpty()) {
				note = ConflictNote.doubleSale(failed.machine(), kept.party(), carrier, placeOf(failed),
						failed.dropped());
			} else {
				// mit strittigen Gruppen der Feldkonflikt samt "Platz", auch ohne Platz: sonst wichen sie still
				List<FieldConflict> conflicts = new ArrayList<>();
				conflicts.add(BookingPolicy.placeConflict(fieldsOf(failed), keptPayload));
				conflicts.addAll(merged.conflicts());
				note = ConflictNote.ofFields(failed.machine(), kept.party(), conflicts,
						carrier.equals(source) ? null : carrier, failed.dropped());
			}
			return new BookingFate(carrier, content, List.of(), List.of(atSource(note)));
		}

		/**
		 * E10: beide gescheitert. Die Buchung lebt "ohne Platz" mit der Fassung des Gewinners an der Quelle und den Feldern
		 * des anderen; jede Seite bekommt ihren Doppelverkauf an ihrem Ziel.
		 */
		private BookingFate bothFailed(Side own, Side foreign, boolean ownWinsSource) {
			Side winner = ownWinsSource ? own : foreign;
			Side loser = ownWinsSource ? foreign : own;
			FieldMerge.Result merged = carry(fieldsOf(loser), fieldsOf(winner));
			UnitKey carrier = unassigned();
			return new BookingFate(carrier, BookingPolicy.withoutPlace(merged.merged()), List.of(),
					List.of(atTarget(own, carrier), atTarget(foreign, carrier)));
		}

		/** E11: ein gescheiterter Umzug, die andere Seite unberührt: "ohne Platz", Vermerk an seinem Ziel. */
		private BookingFate aloneFailed(Side failed) {
			UnitKey carrier = unassigned();
			return new BookingFate(carrier, BookingPolicy.withoutPlace(fieldsOf(failed)), List.of(),
					List.of(atTarget(failed, carrier)));
		}

		/**
		 * E13 (KARV-124): Eine Seite hat die Buchung storniert oder umgezogen, die andere nichts getan, aber eine Auflösung
		 * hat die Buchung unverändert "ohne Platz" geparkt (Doppelverkauf auf dem Stand vor dem Storno oder Umzug). Das
		 * Parken ist keine Absicht: Die geparkte Kopie fällt weg, die Buchung lebt am Ende des Umzugs, wie sie dort ist,
		 * nach einem Storno nirgends. Ohne Vermerk; den Doppelverkauf hält dessen eigener Vermerk fest. Ohne geparkte
		 * Kopie, oder wenn der Umzug in ihrer Einheit selbst endet, gibt es nichts zu entscheiden.
		 */
		private BookingFate againstParked(Side removed) {
			UnitKey copy = unassigned();
			UnitKey carrier = removed.kind() == Kind.CANCELLED ? null : removed.trail().end();
			return !parked || copy.equals(carrier) ? null : new BookingFate(carrier, null, List.of(copy), List.of());
		}

		/** Der Doppelverkauf einer Seite an ihrem gescheiterten Ziel; die Gegenseite dort setzt die Anwendung ein. */
		private Placed atTarget(Side failed, UnitKey carrier) {
			return new Placed(ConflictNote.doubleSale(failed.machine(), null, carrier, placeOf(failed), failed.dropped()),
					failed.trail().end());
		}

		private Placed atSource(ConflictNote note) {
			return new Placed(note, source);
		}

		/** Dreiwegevergleich gegen die Basis: {@code fields} ohne Gruppe "Platz" gegen {@code keeper}, strittig gilt der. */
		private FieldMerge.Result carry(ObjectNode fields, ObjectNode keeper) {
			return BookingPolicy.carryOnto(base.payload(), fields, keeper);
		}

		/** Die Fassung einer Seite mit der Buchung (am Ziel bzw. an der Quelle), sonst die der Basis. */
		private ObjectNode fieldsOf(Side side) {
			return side.trail().last() == null ? base.payload() : side.trail().last().payload();
		}

		/** Der Platz, den die Seite haben wollte (für die Anzeige im Doppelverkauf). */
		private ObjectNode placeOf(Side side) {
			return FieldMerge.slice(fieldsOf(side), BookingPolicy.PLACE);
		}

		/** Die Einheit "ohne Platz" der Buchung in der Vorstellung der Quelle. */
		private UnitKey unassigned() {
			return UnitKey.unassignedBooking(source.eventId(), source.presentationId(), bookingId);
		}
	}
}
