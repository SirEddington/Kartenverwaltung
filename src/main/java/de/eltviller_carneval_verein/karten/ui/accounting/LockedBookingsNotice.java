package de.eltviller_carneval_verein.karten.ui.accounting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.ui.sales.LockedSeats;
import javafx.scene.control.Hyperlink;
import javafx.scene.layout.Region;

/**
 * Der Hinweis der Abrechnung auf gesperrte Buchungen der gewählten Vorstellung (KARV-135; bei "Alle" des ganzen Events),
 * in der Fußzeile vor den Meldungen. Zwei Fälle getrennt: Eine gesperrte Buchung, die im Modell fehlt, ist in der
 * Abrechnung nicht berücksichtigt; eine, deren älterer Stand angezeigt wird (beschädigt nach einem lesbaren Stand, oder
 * eine Formatsperre, die die Kette am älteren Kopf beendet), ist mit diesem Stand gezählt. Gezählt wird am Stand des
 * Versionsspeichers ({@link StoreEventRepository#lockedUnder}), nicht an der Liste der gesperrten Einträge: Die füllt sich
 * erst mit den Rückmeldungen des Abgleichs. Ein Klick öffnet "Gesperrte Einträge" ({@link LockedSeats#open()}).
 * <p>
 * Unsichtbar und ohne Platz, solange nichts gesperrt ist. Nicht threadsicher: alle Aufrufe im JavaFX-Thread.
 */
public final class LockedBookingsNotice extends Hyperlink {

	private static final Logger LOG = Logger.getLogger(LockedBookingsNotice.class.getName());

	/**
	 * Die gesperrten Buchungen nach Fall.
	 *
	 * @param missing    fehlen im Modell, sind also nicht berücksichtigt
	 * @param olderState stehen mit einem älteren Stand im Modell und sind mit ihm gezählt
	 */
	record Counts(int missing, int olderState) {
		static final Counts NONE = new Counts(0, 0);
	}

	/** Für die FXML: zunächst ohne Text und unsichtbar. */
	public LockedBookingsNotice() {
		getStyleClass().add("locked-indicator");
		// in voller Breite: kürzen soll die Fußzeile die Meldung daneben (StatusLabel)
		setMinWidth(Region.USE_PREF_SIZE);
		setOnAction(event -> {
			// ein Link, der immer wieder gilt, ist nie "besucht"
			setVisited(false);
			LockedSeats.open();
		});
		show(Counts.NONE);
	}

	/**
	 * Zählt die gesperrten Buchungen der Auswahl neu und zeigt oder versteckt den Hinweis. Lässt sich nicht zählen, bleibt
	 * der Hinweis weg (Log auf WARNING): Die Abrechnung soll trotzdem aufgehen.
	 *
	 * @param presentation die gewählte Vorstellung; {@code null} für "Alle (Gesamtbilanz)"
	 */
	public void refresh(StoreEventRepository repository, Event event, Presentation presentation) {
		Counts counts = Counts.NONE;
		try {
			counts = count(repository, event, presentation);
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Die gesperrten Buchungen der Abrechnung ließen sich nicht zählen", e);
		}
		show(counts);
	}

	/** Zeigt den Text zu {@code counts}; ohne Text unsichtbar und ohne Platz. */
	void show(Counts counts) {
		String text = text(counts.missing(), counts.olderState());
		setText(text);
		setVisible(!text.isEmpty());
		setManaged(!text.isEmpty());
	}

	/**
	 * Die gesperrten Buchungs-Einheiten der Vorstellung (bzw. des ganzen Events) aus {@link StoreEventRepository#lockedUnder}:
	 * "fehlend", wenn das Modell keine Buchung mit Daten auf diesem Schlüssel hat
	 * ({@link StoreEventRepository#keysOfBookingsWithData}, wie beim Abgleich der Sitze), sonst "älterer Stand".
	 *
	 * @param presentation {@code null} für alle Vorstellungen des Events
	 */
	static Counts count(StoreEventRepository repository, Event event, Presentation presentation) {
		if (event == null) {
			return Counts.NONE;
		}
		UnitKey root = presentation == null ? UnitKey.event(event.getId())
				: UnitKey.presentation(event.getId(), presentation.getId());
		Map<UnitKey, UnitState.Status> locked = repository.lockedUnder(root);
		if (locked.isEmpty()) {
			return Counts.NONE;
		}
		Set<UnitKey> shown = shownBookings(repository, event, presentation);
		int missing = 0;
		int olderState = 0;
		for (UnitKey unit : locked.keySet()) {
			if (unit.type() != UnitType.BOOKING) {
				continue;
			}
			if (shown.contains(unit)) {
				olderState++;
			} else {
				missing++;
			}
		}
		return new Counts(missing, olderState);
	}

	/** Die Schlüssel der Buchungen mit Daten im Modell, in der Vorstellung bzw. allen Vorstellungen des Events. */
	private static Set<UnitKey> shownBookings(StoreEventRepository repository, Event event, Presentation presentation) {
		List<Presentation> presentations = presentation != null ? List.of(presentation)
				: event.getPresentations() != null ? event.getPresentations() : List.of();
		Set<UnitKey> shown = new HashSet<>();
		for (Presentation p : presentations) {
			shown.addAll(repository.keysOfBookingsWithData(event.getId(), p));
		}
		return shown;
	}

	/**
	 * Der Text des Hinweises, ohne JavaFX: {@code 1 gesperrte Buchung ist nicht berücksichtigt} bzw.
	 * {@code n gesperrte Buchungen sind nicht berücksichtigt}, {@code 1 Buchung ist mit einem älteren Stand gezählt} bzw.
	 * {@code n Buchungen sind mit einem älteren Stand gezählt}; beide mit {@code " · "} verbunden.
	 *
	 * @return leer, wenn beide Zahlen 0 sind
	 */
	public static String text(int missing, int olderState) {
		List<String> parts = new ArrayList<>(2);
		if (missing > 0) {
			parts.add(missing == 1 ? "1 gesperrte Buchung ist nicht berücksichtigt"
					: missing + " gesperrte Buchungen sind nicht berücksichtigt");
		}
		if (olderState > 0) {
			parts.add(olderState == 1 ? "1 Buchung ist mit einem älteren Stand gezählt"
					: olderState + " Buchungen sind mit einem älteren Stand gezählt");
		}
		return String.join(" · ", parts);
	}
}
