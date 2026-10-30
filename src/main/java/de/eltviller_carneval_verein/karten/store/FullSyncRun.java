package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

	/** Die Arbeit an einem Block: gleicht die Einheiten ab. */
	interface Work {
		/** Gleicht die Einheiten ab. */
		void sync(List<UnitKey> units) throws IOException;
	}

	/** Ein Abschnitt: {@code presentation} ist nur bei einem Abschnitt "Vorstellung" gesetzt, sonst stehen die Einheiten in {@code units}. */
	private record Section(List<UnitKey> units, UnitKey presentation) {
	}

	private final VersionStore local;
	private final VersionStore shared;
	private final Clock clock;

	private List<Section> sections;
	private int index;
	/** Ob vom aktuellen Abschnitt "Vorstellung" schon ein Block erledigt ist (dann gilt der Zeiger). */
	private boolean started;
	/**
	 * Ob der letzte Block des aktuellen Abschnitts "Vorstellung" scheiterte: Er kann schon einen Teil abgeglichen (und
	 * gemeldet) haben, der Rest folgt erst mit der Wiederholung.
	 */
	private boolean failed;
	/** Der Ordnername der zuletzt bearbeiteten Buchung des aktuellen Abschnitts; leer vor dem ersten Block. */
	private String pointer = "";

	FullSyncRun(VersionStore local, VersionStore shared, Clock clock) {
		this.local = local;
		this.shared = shared;
		this.clock = clock;
	}

	/** Stellt die Abschnitte aus den Beständen beider Seiten zusammen und beginnt von vorn. */
	void begin() throws IOException {
		List<Section> planned = new ArrayList<>();
		List<String> halls = FullSync.union(local.hallIds(), shared.hallIds());
		for (int from = 0; from < halls.size(); from += HALL_BUNDLE) {
			List<UnitKey> bundle = new ArrayList<>();
			for (String hallId : halls.subList(from, Math.min(halls.size(), from + HALL_BUNDLE))) {
				bundle.add(UnitKey.hall(hallId));
			}
			planned.add(new Section(bundle, null));
		}
		for (String eventId : FullSync.union(local.eventIds(), shared.eventIds())) {
			UnitKey event = UnitKey.event(eventId);
			planned.add(new Section(List.of(event), null));
			for (UnitKey presentation : FullSync.childrenOfBoth(local, shared, event, VersionStore.PRESENTATIONS)) {
				planned.add(new Section(List.of(), presentation));
			}
		}
		sections = planned;
		index = 0;
		started = false;
		failed = false;
		pointer = "";
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
			if (section.presentation() == null) {
				work.sync(section.units());
				index++;
			} else {
				stepPresentation(section.presentation(), work);
			}
			last = Duration.between(begin, clock.instant());
		}
		return true;
	}

	/** Ein Block der Vorstellung; ist es der letzte, ist der Abschnitt danach erledigt. */
	private void stepPresentation(UnitKey presentation, Work work) throws IOException {
		List<UnitKey> units = new ArrayList<>();
		if (!started) {
			units.add(presentation);
		}
		int bookings = 0;
		String last = pointer;
		boolean more = false;
		for (UnitKey booking : FullSync.childrenOfBoth(local, shared, presentation, VersionStore.BOOKINGS)) {
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
			work.sync(units);
			failed = false;
		}
		if (more) {
			started = true;
			pointer = last;
		} else {
			index++;
			started = false;
			pointer = "";
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
	 * gescheiterter Block kann schon einen Teil abgeglichen und gemeldet haben.
	 */
	boolean inProgress(UnitKey unit) {
		if (!started && !failed || sections == null || index >= sections.size()) {
			return false;
		}
		UnitKey presentation = sections.get(index).presentation();
		return presentation != null && (unit.equals(presentation) || presentation.equals(unit.parent()));
	}

	/** Ob alle Abschnitte erledigt sind (nach {@link #begin}). */
	boolean finished() {
		return sections != null && index >= sections.size();
	}
}
