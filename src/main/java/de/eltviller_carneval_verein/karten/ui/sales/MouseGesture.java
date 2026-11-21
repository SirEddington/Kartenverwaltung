package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Eine Mausbedienung der Kartentabelle (KARV-193, Spec Abschnitt 14.5): Sie beginnt mit einem Druck in einer Zeile und
 * endet nach dem Loslassen plus {@link #AFTERGLOW_MILLIS} (die übliche Doppelklickzeit von Windows, als feste Größe).
 * Solange sie läuft, wartet jeder Neuaufbau ({@link TicketEdits}); so liegt unter dem zweiten Klick eines Doppelklicks
 * dieselbe Zeile wie unter dem ersten.
 *
 * <p>Ohne JavaFX: Was als Druck, Loslassen und Fokusverlust zählt, meldet der Controller (Filter an der Szene); Uhr und
 * Planer kommen von außen, damit Tests ohne echte Zeit auskommen. Eine geplante Prüfung, die zu früh kommt, wartet den
 * Rest ab; eine, die ein neuer Druck überholt hat, gilt nicht mehr.
 */
final class MouseGesture {

	/** Nachlaufzeit nach dem Loslassen. */
	static final long AFTERGLOW_MILLIS = 500;

	private final LongSupplier clockMillis;
	private final BiConsumer<Long, Runnable> schedule;
	private final Runnable ended;

	private boolean active;
	/** Die Taste gilt als gedrückt: Loslassen steht noch aus. */
	private boolean buttonDown;
	private long releasedAt;
	/** Zählt Druck und Ende; eine geplante Prüfung gilt nur für ihren Stand. */
	private int generation;

	/**
	 * @param clockMillis die Uhr in Millisekunden, muss monoton sein (in der App {@code System.nanoTime() / 1_000_000})
	 * @param schedule    führt die Aufgabe nach der Wartezeit in Millisekunden aus, auf dem FX-Thread
	 * @param ended       läuft genau einmal je beendeter Bedienung
	 */
	MouseGesture(LongSupplier clockMillis, BiConsumer<Long, Runnable> schedule, Runnable ended) {
		this.clockMillis = clockMillis;
		this.schedule = schedule;
		this.ended = ended;
	}

	/** Ein Druck in einer Zeile der Tabelle: beginnt eine Bedienung, auch in der Nachlaufzeit der vorigen. */
	void pressed() {
		generation++;
		active = true;
		buttonDown = true;
	}

	/** Ein Loslassen irgendwo in der Szene: Die Bedienung endet nach der Nachlaufzeit. */
	void released() {
		if (!buttonDown) {
			return;
		}
		buttonDown = false;
		releasedAt = clockMillis.getAsLong();
		int planned = generation;
		schedule.accept(AFTERGLOW_MILLIS, () -> check(planned));
	}

	/** Ein Mausereignis ohne gedrückte Taste: Ging das Loslassen verloren, zählt es als Loslassen. */
	void mouseWithoutButton() {
		released();
	}

	/** Das Fenster hat den Fokus verloren (etwa durch einen modalen Dialog): Die Bedienung endet sofort. */
	void focusLost() {
		end();
	}

	/** Die Tabelle ist nicht mehr zu sehen: Die Bedienung endet sofort. */
	void detached() {
		end();
	}

	/** Ob gerade eine Bedienung läuft. */
	boolean active() {
		return active;
	}

	private void check(int planned) {
		if (planned != generation || !active || buttonDown) {
			return; // überholt
		}
		long left = releasedAt + AFTERGLOW_MILLIS - clockMillis.getAsLong();
		if (left > 0) {
			schedule.accept(left, () -> check(planned));
			return;
		}
		end();
	}

	private void end() {
		if (!active) {
			return;
		}
		active = false;
		buttonDown = false;
		generation++;
		ended.run();
	}
}
