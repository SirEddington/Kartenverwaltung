package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Der Plan eines Vollabgleichs in Stücken: Er teilt die Einheiten beider Bestände in Abschnitte und Blöcke und merkt
 * sich, wo er steht, damit der Aufrufer den Abgleich über mehrere Takte verteilen kann. Er gleicht selbst nichts ab;
 * das tut die {@link Work}, die der Aufrufer {@link #step} mitgibt.
 * <p>
 * Die Abschnitte, in dieser Reihenfolge: die Hallen in Bündeln zu {@link #HALL_BUNDLE}; dann je Event (sortiert) ein
 * Abschnitt "Kopf" mit nur der Event-Einheit und je Vorstellung (sortiert) ein Abschnitt "Vorstellung". Ein Abschnitt
 * "Vorstellung" besteht aus Blöcken: der erste hat die Vorstellung und bis zu {@link #BOOKING_BLOCK} Buchungen, jeder
 * weitere bis zu {@link #BOOKING_BLOCK}. Die Buchungen werden bei jedem Block neu aufgelistet; ein Block nimmt die,
 * die größer sind als der Zeiger, den letzten bearbeiteten Buchungsordner. Was während des Laufs hinter dem Zeiger
 * dazukommt, wird also noch erfasst, was davor dazukommt, erst vom nächsten Lauf.
 * <p>
 * Nicht threadsicher; der Aufrufer hält dafür seine Arbeitssperre.
 */
final class FullSyncRun {

	/** Wie viele Hallen ein Abschnitt zusammenfasst. */
	static final int HALL_BUNDLE = 50;
	/**
	 * Wie viele Buchungen ein Block höchstens enthält. Ein Takt beginnt einen weiteren Block nur, wenn der Block, gemessen
	 * an dem davor, noch ins Budget passt ({@link #step}); die Haltezeit der Arbeitssperre ist also höchstens das Budget,
	 * außer ein einzelner Block ist länger (dann ist der erste Block der Takt). Gemessen (Bestand 15.000 Buchungen, lokale
	 * Platte, vor dieser Regel) dauern 50 Buchungen etwa 0,3 s, mit Hochladen bis etwa 0,6 s; bei 100 stieg ein Takt auf
	 * 2,55 s, bei 500 auf 3,3 s.
	 */
	static final int BOOKING_BLOCK = 50;
	/**
	 * Wie oft ein Abschnitt oder Block bei erreichbarem Ordner nacheinander gescheitert sein muss, bevor er Einheit für
	 * Einheit abgearbeitet wird ({@link #step}).
	 */
	static final int FAILURES_BEFORE_SKIP = 3;
	/**
	 * Wie lange der erste dieser Fehlversuche mindestens zurückliegen muss: Eine kurze Sperre (Sync-Client, Virenscanner)
	 * überdauert so viele Takte, ohne dass etwas übersprungen wird.
	 */
	static final Duration SKIP_AFTER = Duration.ofSeconds(60);

	/** Die Arbeit an einem Block: gleicht die Einheiten ab. */
	interface Work {
		/** Gleicht die Einheiten ab. */
		void sync(List<UnitKey> units) throws IOException;

		/**
		 * Ob der gemeinsame Ordner erreichbar ist. Nur ein Fehler bei erreichbarem Ordner zählt als Fehlversuch, und nur bei
		 * erreichbarem Ordner wird übersprungen: Ein weggefallener Ordner ist kein Schaden an einer Einheit.
		 */
		boolean reachable();

		/**
		 * Der Lauf hat die Einheit übersprungen, weil sie sich bei erreichbarem Ordner auch einzeln nicht abgleichen ließ. Was
		 * ihr Abgleich bis zum Fehler erledigt hat, hat {@link #sync} schon gemeldet; der Rest folgt erst in einem späteren Lauf.
		 *
		 * @param subtree ob auch alles darunter übersprungen ist: Ihre Vorstellungen bzw. Buchungen ließen sich nicht auflisten
		 * @param cause   der Fehler des letzten Versuchs
		 */
		void skipped(UnitKey unit, boolean subtree, Exception cause);
	}

	/**
	 * Ein Abschnitt: {@code presentation} ist nur bei einem Abschnitt "Vorstellung" gesetzt; {@code presentationsOf} nur bei
	 * einem Event, dessen Vorstellungen {@link #begin} nicht auflisten konnte (der Abschnitt listet sie nach und wird durch
	 * ihre Abschnitte ersetzt); sonst stehen die Einheiten in {@code units}.
	 */
	private record Section(List<UnitKey> units, UnitKey presentation, UnitKey presentationsOf) {

		static Section of(List<UnitKey> units) {
			return new Section(units, null, null);
		}

		static Section presentation(UnitKey presentation) {
			return new Section(List.of(), presentation, null);
		}

		static Section presentationsOf(UnitKey event) {
			return new Section(List.of(), null, event);
		}
	}

	private final VersionStore local;
	private final VersionStore shared;
	private final Clock clock;

	private List<Section> sections;
	private int index;
	/**
	 * Ob der letzte Block des aktuellen Abschnitts "Vorstellung" scheiterte: Er kann schon einen Teil abgeglichen (und
	 * gemeldet) haben, der Rest folgt erst mit der Wiederholung.
	 */
	private boolean failed;
	/**
	 * Der Ordnername der zuletzt bearbeiteten Buchung des aktuellen Abschnitts; leer vor dem ersten Block. Ein Buchungsordner
	 * heißt nie leer: Nicht leer heißt also, dass vom aktuellen Abschnitt "Vorstellung" schon ein Block erledigt ist.
	 */
	private String pointer = "";
	/** Wie oft der aktuelle Abschnitt oder Block bei erreichbarem Ordner nacheinander scheiterte. */
	private int failures;
	/** Wann der erste dieser Fehlversuche begann; {@code null} ohne. */
	private Instant firstFailure;
	/** Die Vorstellungen und Events, unter denen dieser Lauf etwas übersprungen hat ({@link #skippedTrees()}). */
	private Set<UnitKey> skippedTrees = new HashSet<>();

	FullSyncRun(VersionStore local, VersionStore shared, Clock clock) {
		this.local = local;
		this.shared = shared;
		this.clock = clock;
	}

	/**
	 * Stellt die Abschnitte aus den Beständen beider Seiten zusammen und beginnt von vorn. Lassen sich die Vorstellungen
	 * eines Events nicht auflisten, scheitert der Beginn nicht: Das Event bekommt einen Abschnitt, der sie nachliest und dabei
	 * scheitern darf wie ein Block ({@link #step}). Scheitert das Auflisten der Hallen oder Events selbst, wirft er.
	 */
	void begin() throws IOException {
		List<Section> planned = new ArrayList<>();
		List<String> halls = FullSync.union(local.hallIds(), shared.hallIds());
		for (int from = 0; from < halls.size(); from += HALL_BUNDLE) {
			List<UnitKey> bundle = new ArrayList<>();
			for (String hallId : halls.subList(from, Math.min(halls.size(), from + HALL_BUNDLE))) {
				bundle.add(UnitKey.hall(hallId));
			}
			planned.add(Section.of(bundle));
		}
		for (String eventId : FullSync.union(local.eventIds(), shared.eventIds())) {
			UnitKey event = UnitKey.event(eventId);
			planned.add(Section.of(List.of(event)));
			try {
				planned.addAll(presentationSections(event));
			} catch (IOException | RuntimeException e) {
				planned.add(Section.presentationsOf(event));
			}
		}
		sections = planned;
		index = 0;
		failed = false;
		skippedTrees = new HashSet<>();
		moveTo("");
	}

	/** Je Vorstellung des Events (auf mindestens einer Seite, sortiert) ein Abschnitt "Vorstellung". */
	private List<Section> presentationSections(UnitKey event) throws IOException {
		List<Section> planned = new ArrayList<>();
		for (UnitKey presentation : FullSync.childrenOfBoth(local, shared, event, VersionStore.PRESENTATIONS)) {
			planned.add(Section.presentation(presentation));
		}
		return planned;
	}

	/**
	 * Arbeitet Abschnitte und Blöcke ab, bis der Lauf fertig ist oder die Frist keinen weiteren Block mehr zulässt. Der
	 * erste Block dieses Aufrufs läuft immer: So kommt der Lauf auch auf einer langsamen Freigabe voran, auf der ein einzelner
	 * Block länger dauert als das Budget. Ein weiterer Abschnitt oder Block beginnt nur, wenn er voraussichtlich bis zur Frist
	 * fertig wird, nämlich wenn die Uhr jetzt plus die Dauer des zuletzt erledigten Blocks (mit Auflisten der Buchungen)
	 * nicht nach {@code deadline} liegt. Ein Aufruf hält die Arbeitssperre des Aufrufers also höchstens bis zur Frist, es sei
	 * denn, ein einzelner Block ist länger oder ein Block wird langsamer als der davor. Wirft {@code work}, bleiben
	 * Abschnitt und Zeiger stehen, und der nächste Aufruf wiederholt den Block; die Vorstellung eines gescheiterten Blocks
	 * gilt bis dahin als in Arbeit ({@link #inProgress}).
	 * <p>
	 * Ein Block, der dauerhaft scheitert, hält den Lauf nicht fest: Ist er bei erreichbarem Ordner
	 * {@link #FAILURES_BEFORE_SKIP}-mal nacheinander gescheitert und liegt der erste dieser Versuche mindestens
	 * {@link #SKIP_AFTER} zurück, wird er Einheit für Einheit abgeglichen. Eine Einheit, die auch dann scheitert, wird
	 * übersprungen ({@link Work#skipped}), die übrigen kommen dran, und der Lauf geht weiter; scheitern alle Einheiten eines
	 * Blocks mit mehreren, wird nichts übersprungen, und der Block wird wieder als Ganzes versucht. Lassen sich die Buchungen
	 * einer Vorstellung oder die Vorstellungen eines Events nicht auflisten, wird die Vorstellung bzw. das Event mit allem
	 * darunter übersprungen. Ist der Ordner beim Fehler nicht erreichbar, zählt er nicht, die Fehlversuche zählen danach von
	 * vorn, und übersprungen wird nichts. Unter einer Vorstellung oder einem Event, unter dem etwas übersprungen wurde, gilt
	 * bis zum Ende des Laufs alles als in Arbeit ({@link #inProgress}).
	 *
	 * @param deadline die Zeit, bis zu der der Aufruf fertig sein soll (nach der Uhr des Aufrufers)
	 * @return ob der Lauf fertig ist
	 */
	boolean step(Instant deadline, Work work) throws IOException {
		if (sections == null) {
			throw new IllegalStateException("Der Lauf wurde nicht begonnen.");
		}
		Duration last = null;
		while (index < sections.size()) {
			Instant begin = clock.instant();
			if (last != null && begin.plus(last).isAfter(deadline)) {
				return false;
			}
			Section section = sections.get(index);
			boolean alone = failures >= FAILURES_BEFORE_SKIP && !begin.isBefore(firstFailure.plus(SKIP_AFTER));
			try {
				if (section.presentationsOf() != null) {
					listPresentations(section.presentationsOf(), work, alone);
				} else if (section.presentation() != null) {
					stepPresentation(section.presentation(), work, alone);
				} else {
					sync(section.units(), null, work, alone);
					advance();
				}
			} catch (IOException | RuntimeException e) {
				noteFailure(work, begin, alone);
				throw e;
			}
			last = Duration.between(begin, clock.instant());
		}
		return true;
	}

	/**
	 * Zählt einen Fehlversuch, der bei {@code begin} begann. Einzeln wirft nur, was nicht an einer Einheit liegt: Dann
	 * beginnt das Zählen neu, und der Block wird wieder als Ganzes versucht. Ist der Ordner nicht erreichbar, zählen die
	 * Fehlversuche danach von vorn, sonst zählte die Auszeit zu {@link #SKIP_AFTER}.
	 */
	private void noteFailure(Work work, Instant begin, boolean alone) {
		if (!work.reachable()) {
			moveTo(pointer);
		} else if (alone || firstFailure == null) {
			failures = 1;
			firstFailure = begin;
		} else {
			failures++;
		}
	}

	/**
	 * Gleicht die Einheiten ab: als einen Block oder, mit {@code alone}, jede für sich; dann werden die Einheiten, die bei
	 * erreichbarem Ordner scheitern, übersprungen, und {@code tree} (die Vorstellung des Blocks; {@code null} bei Hallen und
	 * Event-Köpfen, die keine Geschwister haben) gilt als übersprungen. Scheitern dabei alle Einheiten eines Blocks mit
	 * mehreren, liegt der Fehler nicht an ihnen (etwa die Platte voll oder keine Schreibrechte im gemeinsamen Ordner): Dann
	 * wird nichts übersprungen, und der Fehler geht weiter wie bei einem Block. Die Erreichbarkeit wird einmal nach dem
	 * Block geprüft, nicht je Einheit.
	 */
	private void sync(List<UnitKey> units, UnitKey tree, Work work, boolean alone) throws IOException {
		if (!alone) {
			work.sync(units);
			return;
		}
		Map<UnitKey, Exception> failedUnits = new LinkedHashMap<>();
		for (UnitKey unit : units) {
			try {
				work.sync(List.of(unit));
			} catch (IOException | RuntimeException e) {
				failedUnits.put(unit, e);
			}
		}
		if (failedUnits.isEmpty()) {
			return;
		}
		if (failedUnits.size() == units.size() && units.size() > 1 || !work.reachable()) {
			rethrow(failedUnits.values().iterator().next());
		}
		if (tree != null) {
			skippedTrees.add(tree);
		}
		failedUnits.forEach((unit, cause) -> work.skipped(unit, false, cause));
	}

	/**
	 * Unter dem Event bzw. der Vorstellung ließ sich nichts auflisten: Einzeln und bei erreichbarem Ordner wird sie mit allem
	 * darunter übersprungen, und der nächste Abschnitt ist dran; sonst geht der Fehler weiter.
	 */
	private void skipTree(UnitKey tree, Exception e, Work work, boolean alone) throws IOException {
		if (!alone || !work.reachable()) {
			rethrow(e);
		}
		skippedTrees.add(tree);
		work.skipped(tree, true, e);
		advance();
	}

	/** Wirft den Fehler unverändert weiter; er ist eine {@link IOException} oder eine {@link RuntimeException}. */
	private static void rethrow(Exception e) throws IOException {
		if (e instanceof IOException io) {
			throw io;
		}
		throw (RuntimeException) e;
	}

	/** Der nächste Abschnitt ist dran; ein gescheiterter Block des vorigen zählt nicht mehr. */
	private void advance() {
		index++;
		failed = false;
		moveTo("");
	}

	/** Setzt den Zeiger; die Fehlversuche zählen für den neuen Block von vorn. */
	private void moveTo(String newPointer) {
		pointer = newPointer;
		failures = 0;
		firstFailure = null;
	}

	/** Listet die Vorstellungen des Events nach und setzt ihre Abschnitte an die Stelle dieses Abschnitts. */
	private void listPresentations(UnitKey event, Work work, boolean alone) throws IOException {
		List<Section> planned;
		try {
			planned = presentationSections(event);
		} catch (IOException | RuntimeException e) {
			skipTree(event, e, work, alone);
			return;
		}
		sections.remove(index);
		sections.addAll(index, planned);
		moveTo("");
	}

	/** Ein Block der Vorstellung; ist es der letzte, ist der Abschnitt danach erledigt. */
	private void stepPresentation(UnitKey presentation, Work work, boolean alone) throws IOException {
		List<UnitKey> all;
		try {
			all = FullSync.childrenOfBoth(local, shared, presentation, VersionStore.BOOKINGS);
		} catch (IOException | RuntimeException e) {
			skipTree(presentation, e, work, alone);
			return;
		}
		List<UnitKey> units = new ArrayList<>();
		if (pointer.isEmpty()) {
			units.add(presentation);
		}
		int bookings = 0;
		String last = pointer;
		boolean more = false;
		for (UnitKey booking : all) {
			if (booking.bookingDir().compareTo(pointer) > 0) {
				if (bookings == BOOKING_BLOCK) {
					more = true;
					break;
				}
				units.add(booking);
				last = booking.bookingDir();
				bookings++;
			}
		}
		if (!units.isEmpty()) {
			failed = true;
			sync(units, presentation, work, alone);
			failed = false;
		}
		if (more) {
			moveTo(last);
		} else {
			advance();
		}
	}

	/** Der Anteil der erledigten Abschnitte; {@code 1.0}, wenn der Lauf fertig ist. */
	double progress() {
		if (sections == null || sections.isEmpty()) {
			return finished() ? 1.0 : 0.0;
		}
		return (double) index / sections.size();
	}

	/**
	 * Ob die Einheit zu der Vorstellung gehört (die Vorstellung selbst oder eine Buchung darunter), von der schon Blöcke
	 * erledigt sind, die letzten aber noch nicht, oder deren letzter Block scheiterte (bis ein Block gelingt): Ein
	 * gescheiterter Block kann schon einen Teil abgeglichen und gemeldet haben. In Arbeit bleibt bis zum Ende des Laufs auch
	 * alles unter einer Vorstellung oder einem Event, unter dem er etwas übersprungen hat ({@link #skippedTrees()}).
	 */
	boolean inProgress(UnitKey unit) {
		if (covers(skippedTrees, unit)) {
			return true;
		}
		if (pointer.isEmpty() && !failed || sections == null || index >= sections.size()) {
			return false;
		}
		UnitKey presentation = sections.get(index).presentation();
		return presentation != null && (unit.equals(presentation) || presentation.equals(unit.parent()));
	}

	/** Die Vorstellungen und Events, unter denen dieser Lauf etwas übersprungen hat, als unveränderliche Kopie. */
	Set<UnitKey> skippedTrees() {
		return Set.copyOf(skippedTrees);
	}

	/** Ob die Einheit oder eine darüber in der Menge steht. */
	static boolean covers(Set<UnitKey> trees, UnitKey unit) {
		for (UnitKey u = unit; u != null && !trees.isEmpty(); u = u.parent()) {
			if (trees.contains(u)) {
				return true;
			}
		}
		return false;
	}

	/** Ob alle Abschnitte erledigt sind (nach {@link #begin}). */
	boolean finished() {
		return sections != null && index >= sections.size();
	}
}
