package de.eltviller_carneval_verein.karten.store;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Leitet die offenen Konflikte dieses Rechners aus den Einträgen des Start-Index ab (Spezifikation, "Konflikt-Ledger" und
 * "Elternlöschung"). Reine Rechnung auf den Einträgen, ohne Dateizugriff; der Index spiegelt nur, was {@link ChainResolver}
 * entschieden hat.
 * <ul>
 * <li><b>Vermerk:</b> zählt, wenn {@link ConflictNote#affected()} dieser Rechner ist. Der Index hält Vermerke nur von
 * Versionen in der Kette, in den aufgegangenen und unter den eigenen ausstehenden ({@link UnitIndex.Entry#notes()}).</li>
 * <li><b>Elternlöschung:</b> Die aktuelle Version eines Event-Kopfs oder einer Vorstellung ist eine <em>fremde</em>
 * Löschung {@code D}. Darunter zählt jede eigene Einheit (Buchung, bei einem Event-Kopf auch Vorstellung), deren aktueller
 * Stand eine eigene, nicht gelöschte Version ist und deren Listennummer {@code 0} ist (in keiner Liste) oder größer als
 * {@code D.seen[eigene ID]} (fehlt der Eintrag: 0): Der Löschende kannte sie nicht. Eine eigene Zusammenführung, in der
 * "Deren" blieb (Vermerk DOUBLE_SALE oder UNMERGED), trägt zwar die eigene ID, aber die Buchung der Gegenseite und zählt
 * deshalb nicht. Eine eigene Löschung ist kein
 * Konflikt, außer sie ist die Zusammenführung, in der eine fremde Löschung gegen eine eigene Änderung gewann
 * (Vermerk DELETE_WINS mit fremder Gegenseite): Sie gilt als die fremde Löschung.</li>
 * <li><b>Unbekannte Listennummer</b> ({@code ownSeq == null} bei einer eigenen Version): zählt weder als Konflikt noch als
 * bekannt; {@link Derivation#undecided()} sagt dem Aufrufer, dass er erst die eigenen Listen lesen muss.</li>
 * </ul>
 * Ob eine Version eigen ist, folgt aus dem Kürzel in ihrer ID ({@link Ownership#isOwnId}: aktuelle ID, oder eine frühere bis
 * zu ihrer Neuvergabe); der Eintrag nennt den Rechner der aktuellen Version nicht. Ein Vermerk betrifft diesen Rechner, wenn
 * {@link ConflictNote#affected()} eine eigene ID ist und die weichende Version ({@link ConflictNote#dropped()}, ohne sie die
 * Version mit dem Vermerk) bei einer früheren ID vor deren Neuvergabe entstand ({@link #affectsMe}). Die Listennummern
 * ({@link UnitIndex.Entry#ownSeq()}) stammen immer aus den Listen der aktuellen ID (bei einer Neuvergabe vergisst der Index
 * die Nummern aus den Listen der früheren, {@link UnitIndex#adoptOwnership}) und werden deshalb mit
 * {@code D.seen[aktuelle ID]} verglichen.
 */
final class ConflictLedger {

	/**
	 * Das Ergebnis einer Ableitung.
	 *
	 * @param conflicts die offenen Konflikte, nach Zeitpunkt, dann ID sortiert
	 * @param undecided ob eine Elternlöschung wegen einer eigenen Version mit unbekannter Listennummer nicht entschieden
	 *                  werden konnte
	 */
	record Derivation(List<OpenConflict> conflicts, boolean undecided) {
	}

	/** Eine fremde Löschung eines Event-Kopfs oder einer Vorstellung und die eigenen Einheiten, die sie nicht kannte. */
	private static final class Deletion {
		final UnitKey parent;
		final ChainStep step;
		final String machineName;
		final String operator;
		final long seenOfMe;
		final List<UnitKey> affected = new ArrayList<>();

		Deletion(UnitKey parent, ChainStep step, String machineName, String operator, long seenOfMe) {
			this.parent = parent;
			this.step = step;
			this.machineName = machineName;
			this.operator = operator;
			this.seenOfMe = seenOfMe;
		}
	}

	private ConflictLedger() {
	}

	/**
	 * @param entries      die Einträge des Index
	 * @param own          was dieser Rechner als eigen ansieht
	 * @param acknowledged die bestätigten Konflikte (Version und Art); sie zählen nicht mehr
	 */
	static Derivation derive(Collection<UnitIndex.Entry> entries, Ownership own, Set<ConflictKey> acknowledged) {
		List<OpenConflict> conflicts = new ArrayList<>();
		Map<UnitKey, Deletion> deletions = new HashMap<>();
		List<Candidate> candidates = new ArrayList<>();
		for (UnitIndex.Entry entry : entries) {
			UnitKey unit = entry.snapshot().unit();
			for (UnitIndex.NotedVersion noted : entry.notes()) {
				if (affectsMe(own, noted)) {
					candidates.add(new Candidate(unit, rank(entry, noted.version()), noted));
				}
			}
			Deletion deletion = foreignParentDeletion(entry, own, acknowledged);
			if (deletion != null) {
				deletions.put(unit, deletion);
			}
		}
		for (Candidate candidate : withoutSuperseded(candidates)) {
			UnitIndex.NotedVersion noted = candidate.noted();
			if (!acknowledged.contains(new ConflictKey(noted.version(), ConflictKey.Kind.NOTE))) {
				conflicts.add(new OpenConflict(noted.version(), candidate.unit(), noted.at(), noted.saveId(), noted.note(),
						null));
			}
		}
		boolean undecided = !deletions.isEmpty() && collectAffected(entries, own, deletions);
		for (Deletion deletion : deletions.values()) {
			if (!deletion.affected.isEmpty()) {
				deletion.affected.sort(Comparator.comparing(UnitKey::relativePath));
				ChainStep step = deletion.step;
				conflicts.add(new OpenConflict(step.version(), deletion.parent, step.at(), step.saveId(), null,
						new OpenConflict.ParentDeletion(deletion.parent, deletion.machineName, deletion.operator, deletion.affected)));
			}
		}
		conflicts.sort(Comparator.comparing(OpenConflict::at).thenComparing(OpenConflict::id));
		return new Derivation(List.copyOf(conflicts), undecided);
	}

	/**
	 * Ein Vermerk für diesen Rechner, mit seiner Stelle in der Einheit.
	 *
	 * @param unit  die Einheit, an deren Version der Vermerk steht
	 * @param rank  die Stelle: aufgegangen {@code 0}, in der Kette {@code 1 +} Index, eigene ausstehende
	 *              {@link Integer#MAX_VALUE}
	 * @param noted die Version mit dem Vermerk
	 */
	private record Candidate(UnitKey unit, int rank, UnitIndex.NotedVersion noted) {
	}

	/** Die Stelle der Version in ihrer Einheit (siehe {@link Candidate#rank()}); die Reihenfolge ist kausal. */
	private static int rank(UnitIndex.Entry entry, VersionId version) {
		if (entry.absorbed().contains(version)) {
			return 0;
		}
		List<ChainStep> chain = entry.chain();
		for (int i = 0; i < chain.size(); i++) {
			if (chain.get(i).version().equals(version)) {
				return 1 + i;
			}
		}
		return Integer.MAX_VALUE;
	}

	/**
	 * Ohne die Vermerke, hinter denen ein anderer zum selben verlorenen Stand ({@link ConflictNote#dropped()}) zurücktritt
	 * (KARV-98): Folgeauflösungen schreiben ihn erneut, und der Dialog zeigte denselben Verlust mehrfach. Die Regeln
	 * kommen ohne Uhr aus (die Versions-ID beginnt mit der Uhrzeit des Schreibenden), siehe {@link #supersedes}. Vermerke
	 * ohne {@code dropped} bleiben alle.
	 */
	private static List<Candidate> withoutSuperseded(List<Candidate> candidates) {
		List<Candidate> kept = new ArrayList<>();
		for (Candidate candidate : candidates) {
			if (candidate.noted().note().dropped() == null
					|| candidates.stream().noneMatch(other -> other != candidate && supersedes(other, candidate))) {
				kept.add(candidate);
			}
		}
		return kept;
	}

	/**
	 * Ob {@code later} denselben verlorenen Stand später nennt als {@code earlier}:
	 * <ul>
	 * <li>In derselben Einheit an einer späteren Stelle (aufgegangen, Kette, ausstehend); zwei aufgegangene, wenn
	 * {@code later} die andere als Vorgänger hat. Zwei "Löschung gilt" dort auch mit verschiedenem {@code dropped}, wenn
	 * {@code later} die andere als Vorgänger hat (eine Neu-Entscheidung über sie).</li>
	 * <li>An einer anderen Einheit, wenn {@code earlier} ein Doppelverkauf oder ein Feldkonflikt mit Träger ist und sein
	 * {@code moved} (wohin die Buchung kam) diese Einheit nennt: Dort hat eine spätere Auflösung mit Kenntnis des Parkens
	 * entschieden. Bei "Löschung gilt" nennt {@code moved}, wo die weichende Fassung liegt; er tritt so nie zurück.</li>
	 * </ul>
	 */
	private static boolean supersedes(Candidate later, Candidate earlier) {
		ConflictNote before = earlier.noted().note();
		ConflictNote after = later.noted().note();
		if (later.unit().equals(earlier.unit()) && later.rank() > earlier.rank()
				&& before.kind() == ConflictNote.Kind.DELETE_WINS && after.kind() == ConflictNote.Kind.DELETE_WINS
				&& later.noted().parents().contains(earlier.noted().version())) {
			// die Buchung ist gelöscht, und die spätere Auflösung entschied über die frühere neu: Ihr "Löschung gilt"
			// ersetzt das frühere, auch mit anderer weichender Fassung (etwa eine Bearbeitung nach einer Auflösung);
			// wiederhergestellt wird die jüngere
			return true;
		}
		if (!before.dropped().equals(after.dropped())) {
			return false;
		}
		if (later.unit().equals(earlier.unit())) {
			if (later.rank() != earlier.rank()) {
				return later.rank() > earlier.rank();
			}
			return earlier.rank() == 0 && later.noted().parents().contains(earlier.noted().version());
		}
		boolean placed = before.kind() == ConflictNote.Kind.DOUBLE_SALE
				|| before.kind() == ConflictNote.Kind.FIELDS && before.moved() != null;
		return placed && later.unit().equals(before.moved());
	}

	/**
	 * Ob der Vermerk diesen Rechner betrifft: {@link ConflictNote#affected()} ist eine eigene ID, gemessen an der Zeit der
	 * weichenden Version ({@link ConflictNote#dropped()}; ohne sie an der Version, die den Vermerk trägt). So zählt auch ein
	 * Vermerk, den ein anderer Rechner nach der Neuvergabe über eine Änderung von vor ihr schrieb, und keiner über die
	 * Änderung des Rechners, der die alte ID weiter trägt.
	 */
	static boolean affectsMe(Ownership own, UnitIndex.NotedVersion noted) {
		ConflictNote note = noted.note();
		return own.isOwn(note.affected(), note.dropped() != null ? note.dropped() : noted.version());
	}

	/**
	 * Die fremde Löschung, die der Eintrag als aktuellen Stand hat; {@code null}, wenn er keine hat oder sie bestätigt ist.
	 * Als fremd gilt auch eine eigene Zusammenführung, die als Löschung entstand, weil die fremde Löschung gegen eine eigene
	 * Änderung gewann (Vermerk DELETE_WINS, dessen Gegenseite ein anderer Rechner ist): Sie trägt {@code seen} der fremden
	 * Löschung, Rechner und Bediener nennt der Vermerk.
	 */
	private static Deletion foreignParentDeletion(UnitIndex.Entry entry, Ownership own, Set<ConflictKey> acknowledged) {
		UnitSnapshot snapshot = entry.snapshot();
		VersionId version = snapshot.version();
		if (!snapshot.deleted() || version == null || !snapshot.unit().type().hasChildren()
				|| acknowledged.contains(new ConflictKey(version, ConflictKey.Kind.PARENT_DELETION))) {
			return null;
		}
		List<ChainStep> chain = entry.chain();
		ChainStep step = chain.isEmpty() ? null : chain.get(chain.size() - 1);
		if (step == null || !step.version().equals(version)) {
			return null;
		}
		String machineName = step.machineName();
		String operator = step.operator();
		if (own.isOwnId(version)) {
			ConflictNote.Party other = deleterOfOwnMerge(entry, version);
			if (other == null || own.isOwn(other)) {
				return null;
			}
			machineName = other.machineName();
			operator = other.operator();
		}
		return new Deletion(snapshot.unit(), step, machineName, operator,
				entry.seen().getOrDefault(own.currentId(), 0L));
	}

	/** Die Gegenseite des Vermerks DELETE_WINS an der Version; {@code null}, wenn sie keinen hat. */
	private static ConflictNote.Party deleterOfOwnMerge(UnitIndex.Entry entry, VersionId version) {
		for (UnitIndex.NotedVersion noted : entry.notes()) {
			if (noted.version().equals(version) && noted.note().kind() == ConflictNote.Kind.DELETE_WINS) {
				return noted.note().other();
			}
		}
		return null;
	}

	/**
	 * Ob die eigene Version nur die ID dieses Rechners trägt, ihr Inhalt aber der der Gegenseite ist: bei einem Doppelverkauf
	 * oder einer unmöglichen Zusammenführung bleibt "Deren", und "Meine" weicht ({@link ConflictNote.Kind#keepsTheirs}). Der
	 * Verkauf darin ist der der Gegenseite, nicht einer, den der Löschende von diesem Rechner nicht kannte (der ausgewichene
	 * eigene steht in seiner eigenen Einheit). Bei gemischtem Inhalt (FIELDS) zählt die Version weiter.
	 */
	private static boolean carriesForeignContent(UnitIndex.Entry entry, VersionId version) {
		for (UnitIndex.NotedVersion noted : entry.notes()) {
			if (noted.version().equals(version) && noted.note().kind().keepsTheirs()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Trägt bei jeder fremden Löschung die eigenen Einheiten darunter ein, die der Löschende nicht kannte.
	 *
	 * @return ob eine eigene Einheit unter einer Löschung eine unbekannte Listennummer hat
	 */
	private static boolean collectAffected(Collection<UnitIndex.Entry> entries, Ownership own, Map<UnitKey, Deletion> deletions) {
		boolean undecided = false;
		for (UnitIndex.Entry entry : entries) {
			UnitSnapshot snapshot = entry.snapshot();
			UnitKey unit = snapshot.unit();
			VersionId version = snapshot.version();
			if (snapshot.deleted() || version == null || !own.isOwnId(version) || carriesForeignContent(entry, version)) {
				continue;
			}
			for (UnitKey parent : unit.ancestors()) {
				Deletion deletion = deletions.get(parent);
				if (deletion == null) {
					continue;
				}
				Long listNumber = entry.ownSeq();
				if (listNumber == null) {
					undecided = true;
				} else if (listNumber == 0 || listNumber > deletion.seenOfMe) {
					deletion.affected.add(unit);
				}
			}
		}
		return undecided;
	}
}
