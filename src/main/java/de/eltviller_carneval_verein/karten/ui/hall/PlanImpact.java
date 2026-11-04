package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/**
 * Welche Buchungen mit Daten ({@link Booking#hasData()}) eine Aktion im Hallen-Editor trifft, samt den Hinweistexten
 * für die Rückfragen beim Löschen von Reihen, Tischen und Sitzen und beim "Nummern neu vergeben". Nur ein Hinweis, kein
 * Schutz: Die Buchungen bleiben erhalten (beim Löschen stehen sie danach unter "Ohne Platz", beim Umnummerieren folgen
 * sie über die Sitzkennung ihren Tischen). Gezählt werden die Buchungen der Vorstellungen, die die Bestuhlung nutzen,
 * deren {@link Booking#getSeatId() Sitzkennung} unter den betroffenen Sitzen ist.
 */
public final class PlanImpact {

	/** Eine Vorstellung mit betroffenen Buchungen; die Namen sind schon für die Anzeige aufbereitet. */
	record Entry(String eventName, String presentationName, int bookings) {
	}

	private final List<Entry> entries;

	private PlanImpact(List<Entry> entries) {
		this.entries = entries;
	}

	/**
	 * Zählt je Vorstellung mit {@code seatingPlanId == planId} die Buchungen mit Daten auf den Sitzen {@code seatIds}.
	 * Alle Argumente dürfen {@code null} sein (dann ist nichts betroffen), ebenso die Sitzkennung einer Buchung (sie
	 * zählt dann nicht; {@code Set.of} lehnt {@code contains(null)} ab).
	 */
	public static PlanImpact of(String planId, Collection<String> seatIds, List<Event> events) {
		List<Entry> entries = new ArrayList<>();
		if (planId == null || seatIds == null || seatIds.isEmpty() || events == null) {
			return new PlanImpact(entries);
		}
		for (Event event : events) {
			if (event == null || event.getPresentations() == null) {
				continue;
			}
			for (Presentation presentation : event.getPresentations()) {
				if (!planId.equals(presentation.getSeatingPlanId()) || presentation.getBookings() == null) {
					continue;
				}
				int count = (int) presentation.getBookings().stream().filter(b -> b.hasData() && b.getSeatId() != null && seatIds.contains(b.getSeatId())).count();
				if (count > 0) {
					entries.add(new Entry(EntityLabels.nameOrPlaceholder(event.getName()), EntityLabels.nameOrPlaceholder(presentation.getName()), count));
				}
			}
		}
		return new PlanImpact(entries);
	}

	/**
	 * {@link #of} über die Events aus dem {@link StoreEventRepository} (wie die Rückfrage beim Löschen einer
	 * Bestuhlung); geladen wird nur, wenn überhaupt Sitze betroffen sind.
	 */
	static PlanImpact inEvents(SeatingPlan plan, Collection<String> seatIds) {
		if (plan == null || seatIds == null || seatIds.isEmpty()) {
			return new PlanImpact(List.of());
		}
		return of(plan.getId(), seatIds, StoreEventRepository.getInstance().loadEvents());
	}

	/** Die Sitzkennungen aller Sitze der Tische ({@code null} ergibt keine). */
	static Set<String> seatIds(Collection<Table> tables) {
		Set<String> ids = new HashSet<>();
		if (tables != null) {
			tables.forEach(table -> table.getSeats().forEach(seat -> ids.add(seat.getId())));
		}
		return ids;
	}

	/**
	 * Die Sitze der Tische, die {@link SeatingPlan#renumber(TableRow)} für diese Reihe eine andere Nummer gäbe. Das
	 * Modell bleibt unverändert: Umnummeriert wird eine {@link SeatingPlan#copy() Kopie}, die Reihen und Tische in
	 * derselben Reihenfolge enthält, verglichen wird Tisch für Tisch an derselben Stelle.
	 */
	static Set<String> seatIdsRenumbered(SeatingPlan plan, TableRow row) {
		Set<String> ids = new HashSet<>();
		int rowIndex = plan == null ? -1 : plan.getRows().indexOf(row);
		if (rowIndex < 0) {
			return ids;
		}
		SeatingPlan copy = plan.copy();
		copy.renumber(copy.getRows().get(rowIndex));
		for (int i = 0; i < plan.getTables().size(); i++) {
			Table table = plan.getTables().get(i);
			if (table.getTableNumber() != copy.getTables().get(i).getTableNumber()) {
				table.getSeats().stream().map(Seat::getId).forEach(ids::add);
			}
		}
		return ids;
	}

	/** Fasst die Auswirkungen mehrerer Bestuhlungen zusammen (jede Vorstellung nutzt nur eine, die Einträge überschneiden sich nicht). */
	public static PlanImpact combine(Collection<PlanImpact> impacts) {
		List<Entry> entries = new ArrayList<>();
		impacts.forEach(impact -> entries.addAll(impact.entries));
		return new PlanImpact(entries);
	}

	List<Entry> entries() {
		return entries;
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	int total() {
		return entries.stream().mapToInt(Entry::bookings).sum();
	}

	/**
	 * Der Zusatz für die Rückfrage vor dem Löschen (beginnt mit einer Leerzeile), ohne betroffene Buchungen leer. Eine
	 * Vorstellung steht im Satz, mehrere je in einer Zeile.
	 */
	public String deleteNote() {
		if (isEmpty()) {
			return "";
		}
		StringBuilder note = new StringBuilder("\n\nBetroffen:");
		if (entries.size() == 1) {
			note.append(' ').append(line(entries.get(0))).append('.');
		} else {
			entries.forEach(entry -> note.append("\n- ").append(line(entry)));
		}
		note.append(total() == 1 ? "\nSie bleibt erhalten und steht danach unter „Ohne Platz“, bis ihr ein neuer Platz zugewiesen wird."
				: "\nSie bleiben erhalten und stehen danach unter „Ohne Platz“, bis ihnen ein neuer Platz zugewiesen wird.");
		return note.toString();
	}

	/** Die Rückfrage vor "Nummern neu vergeben", ohne betroffene Buchungen {@code null} (dann ohne Rückfrage). */
	String renumberMessage() {
		if (isEmpty()) {
			return null;
		}
		if (entries.size() == 1) {
			return "Nummern neu vergeben: " + line(entries.get(0)) + (total() == 1 ? " folgt ihrem Tisch und trägt danach eine andere Tischnummer."
					: " folgen ihren Tischen und tragen danach andere Tischnummern.");
		}
		StringBuilder message = new StringBuilder("Nummern neu vergeben: Diese Buchungen folgen ihren Tischen und tragen danach andere Tischnummern:");
		entries.forEach(entry -> message.append("\n- ").append(line(entry)));
		return message.toString();
	}

	/** "3 Buchungen mit Daten in Vorstellung Prunksitzung (Event Kampagne 2027)". */
	private static String line(Entry entry) {
		return (entry.bookings() == 1 ? "1 Buchung" : entry.bookings() + " Buchungen") + " mit Daten in Vorstellung " + entry.presentationName()
				+ " (Event " + entry.eventName() + ")";
	}
}
