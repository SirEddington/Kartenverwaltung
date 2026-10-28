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
 * {@code D.seen[eigene ID]} (fehlt der Eintrag: 0): Der Löschende kannte sie nicht. Eine eigene Löschung ist kein
 * Konflikt, außer sie ist die Zusammenführung, in der eine fremde Löschung gegen eine eigene Änderung gewann
 * (Vermerk DELETE_WINS mit fremder Gegenseite): Sie gilt als die fremde Löschung.</li>
 * <li><b>Unbekannte Listennummer</b> ({@code ownSeq == null} bei einer eigenen Version): zählt weder als Konflikt noch als
 * bekannt; {@link Derivation#undecided()} sagt dem Aufrufer, dass er erst die eigenen Listen lesen muss.</li>
 * </ul>
 * Ob eine Version eigen ist, folgt aus dem Kürzel in ihrer ID ({@link VersionId#isFrom}); der Eintrag nennt den Rechner der
 * aktuellen Version nicht.
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
	 * @param ownId        die Rechner-ID dieses Rechners
	 * @param acknowledged die bestätigten Konflikte (Version und Art); sie zählen nicht mehr
	 */
	static Derivation derive(Collection<UnitIndex.Entry> entries, String ownId, Set<ConflictKey> acknowledged) {
		List<OpenConflict> conflicts = new ArrayList<>();
		Map<UnitKey, Deletion> deletions = new HashMap<>();
		for (UnitIndex.Entry entry : entries) {
			UnitKey unit = entry.snapshot().unit();
			for (UnitIndex.NotedVersion noted : entry.notes()) {
				if (ownId.equals(noted.note().affected())
						&& !acknowledged.contains(new ConflictKey(noted.version(), ConflictKey.Kind.NOTE))) {
					conflicts.add(new OpenConflict(noted.version(), unit, noted.at(), noted.saveId(), noted.note(), null));
				}
			}
			Deletion deletion = foreignParentDeletion(entry, ownId, acknowledged);
			if (deletion != null) {
				deletions.put(unit, deletion);
			}
		}
		boolean undecided = !deletions.isEmpty() && collectAffected(entries, ownId, deletions);
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
	 * Die fremde Löschung, die der Eintrag als aktuellen Stand hat; {@code null}, wenn er keine hat oder sie bestätigt ist.
	 * Als fremd gilt auch eine eigene Zusammenführung, die als Löschung entstand, weil die fremde Löschung gegen eine eigene
	 * Änderung gewann (Vermerk DELETE_WINS, dessen Gegenseite ein anderer Rechner ist): Sie trägt {@code seen} der fremden
	 * Löschung, Rechner und Bediener nennt der Vermerk.
	 */
	private static Deletion foreignParentDeletion(UnitIndex.Entry entry, String ownId, Set<ConflictKey> acknowledged) {
		UnitSnapshot snapshot = entry.snapshot();
		UnitType type = snapshot.unit().type();
		VersionId version = snapshot.version();
		if (!snapshot.deleted() || version == null || type != UnitType.EVENT && type != UnitType.PRESENTATION
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
		if (version.isFrom(ownId)) {
			ConflictNote.Party other = deleterOfOwnMerge(entry, version);
			if (other == null || ownId.equals(other.machine())) {
				return null;
			}
			machineName = other.machineName();
			operator = other.operator();
		}
		return new Deletion(snapshot.unit(), step, machineName, operator, entry.seen().getOrDefault(ownId, 0L));
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
	 * Trägt bei jeder fremden Löschung die eigenen Einheiten darunter ein, die der Löschende nicht kannte.
	 *
	 * @return ob eine eigene Einheit unter einer Löschung eine unbekannte Listennummer hat
	 */
	private static boolean collectAffected(Collection<UnitIndex.Entry> entries, String ownId, Map<UnitKey, Deletion> deletions) {
		boolean undecided = false;
		for (UnitIndex.Entry entry : entries) {
			UnitSnapshot snapshot = entry.snapshot();
			UnitKey unit = snapshot.unit();
			VersionId version = snapshot.version();
			if (snapshot.deleted() || version == null || !version.isFrom(ownId)) {
				continue;
			}
			for (UnitKey parent : parentsOf(unit)) {
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

	/** Die Einheiten, deren Löschung diese Einheit trifft: bei einer Buchung Vorstellung und Event, bei einer Vorstellung das Event. */
	private static List<UnitKey> parentsOf(UnitKey unit) {
		return switch (unit.type()) {
		case BOOKING -> List.of(UnitKey.presentation(unit.eventId(), unit.presentationId()), UnitKey.event(unit.eventId()));
		case PRESENTATION -> List.of(UnitKey.event(unit.eventId()));
		default -> List.of();
		};
	}
}
