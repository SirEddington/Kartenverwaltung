package de.eltviller_carneval_verein.karten.ui.event;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.BookingSync.Report;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.ui.ConfirmDialog;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import de.eltviller_carneval_verein.karten.validation.Severity;

/**
 * Wechsel der Bestuhlung einer Vorstellung mit Rückfrage: Vorher rechnet {@link BookingSync#preview} die Folgen durch,
 * der Dialog nennt sie ("12 Buchungen übernommen, 3 ohne Platz, 5 leere entfernt"), erst nach der Bestätigung läuft
 * {@link BookingSync#switchPlan}. "Abbrechen" ändert nichts am Modell.
 *
 * <p>
 * Gefragt wird nur, wenn der Wechsel etwas betrifft: eine Buchung mit Daten ({@link Booking#hasData()}) oder eine
 * Buchung, die entfernt würde oder ohne Platz bliebe. Die erste Zuordnung einer Vorstellung ohne Buchungen und der
 * Wechsel auf eine gleich nummerierte Bestuhlung ohne verkaufte Plätze laufen ohne Dialog.
 */
public final class PlanSwitchDialog {

	private PlanSwitchDialog() {
	}

	/**
	 * Wechselt die Vorstellung auf die Bestuhlung, bei Bedarf nach Rückfrage. Liegen unter der Vorstellung gesperrte
	 * Einträge, wird der Wechsel abgelehnt, bevor sich das Modell ändert ({@link LockedEntryGuard}, KARV-135): Sonst bliebe
	 * eine gesperrte Buchung auf ihrem alten Sitz, und der gleichnamige Sitz der neuen Bestuhlung wäre verkäuflich.
	 *
	 * @param newPlan die neue Bestuhlung; {@code null} hebt die Zuordnung auf: leere Buchungen entfallen, Buchungen mit
	 *                Daten bleiben ohne Platz (siehe {@link BookingSync#sync})
	 * @return {@code true}, wenn kein Wechsel nötig war oder der Wechsel angewendet wurde; {@code false} bei "Abbrechen"
	 *         oder wenn er abgelehnt wurde (die Meldung steht dann in der Fußzeile)
	 */
	public static boolean confirm(Presentation p, SeatingPlan newPlan) {
		return confirm(p, newPlan, StoreEventRepository.getInstance(), report -> ask(p, newPlan, report),
				message -> StatusMessage.getInstance().show(Severity.ERROR, message));
	}

	/**
	 * Wie {@link #confirm(Presentation, SeatingPlan)} mit dem Repository {@code repository}; die Rückfrage kommt von
	 * {@code ask}, die Meldung einer Ablehnung geht an {@code reject} (paketsichtbar für Tests).
	 */
	static boolean confirm(Presentation p, SeatingPlan newPlan, StoreEventRepository repository, Predicate<Report> ask,
			Consumer<String> reject) {
		// ohne Wechsel gibt es nichts abzulehnen
		if (!isSamePlan(p, newPlan) && LockedEntryGuard.rejects(p, repository, reject)) {
			return false;
		}
		return confirm(p, newPlan, () -> repository.lockedSeatIds(p.getParent().getId(), p.getId()), ask);
	}

	private static boolean isSamePlan(Presentation p, SeatingPlan newPlan) {
		return Objects.equals(newPlan != null ? newPlan.getId() : null, p.getSeatingPlanId());
	}

	/**
	 * Der Wechsel ohne die Prüfung auf gesperrte Einträge: Die gesperrten Sitze ({@link StoreEventRepository#lockedSeatIds})
	 * kommen von {@code lockedSeats}, die Rückfrage von {@code ask} (paketsichtbar für Tests).
	 */
	static boolean confirm(Presentation p, SeatingPlan newPlan, Supplier<Set<String>> lockedSeats,
			Predicate<Report> ask) {
		if (isSamePlan(p, newPlan)) {
			return true;
		}
		if (newPlan == null) {
			// ohne Bestuhlung gibt es keine Sitze, also auch keine gesperrten
			BookingSync.switchPlan(p, null, Set.of());
			return true;
		}
		Set<String> locked = lockedSeats.get();
		Report shown = dialogReport(p, BookingSync.preview(p, newPlan, locked));
		if (needsConfirmation(shown) && !ask.test(shown)) {
			return false;
		}
		BookingSync.switchPlan(p, newPlan, locked);
		return true;
	}

	/**
	 * Die Zahlen für den Dialog: {@link Report#matched()} der Vorschau zählt auch leere Buchungen, die einen Sitz finden.
	 * "Übernommen" heißt im Dialog aber nur eine Buchung mit Daten, die einen Sitz findet. Jede Buchung mit Daten
	 * findet beim Abgleich entweder einen Sitz oder bleibt ohne Platz, daher: Buchungen mit Daten minus ohne Platz.
	 */
	static Report dialogReport(Presentation p, Report preview) {
		int withData = 0;
		if (p.getBookings() != null) {
			for (Booking booking : p.getBookings()) {
				if (booking.hasData()) {
					withData++;
				}
			}
		}
		return new Report(withData - preview.unassigned(), preview.created(), preview.removedEmpty(), preview.unassigned());
	}

	/** Etwas zu nennen gibt es, wenn eine Buchung mit Daten betroffen ist oder Buchungen entfernt würden. */
	static boolean needsConfirmation(Report shown) {
		return shown.matched() > 0 || shown.unassigned() > 0 || shown.removedEmpty() > 0;
	}

	/** z. B. "12 Buchungen übernommen, 3 ohne Platz, 5 leere entfernt" ({@code matched} = übernommen). */
	static String summary(Report r) {
		return r.matched() + (r.matched() == 1 ? " Buchung" : " Buchungen") + " übernommen, " + r.unassigned() + " ohne Platz, "
				+ r.removedEmpty() + " leere entfernt";
	}

	/**
	 * Die Folgen des Wechsels als Text für eine Rückfrage, ohne die Vorstellung zu verändern: für Aufrufer, die den Wechsel
	 * selbst ausführen (der Sammel-Dialog übernimmt die Werte über das Repository).
	 *
	 * @param lockedSeats die gesperrten Sitze der Vorstellung ({@link StoreEventRepository#lockedSeatIds})
	 * @return der Text; {@code null}, wenn es nichts zu nennen gibt (kein Wechsel, keine Bestuhlung oder keine betroffene Buchung)
	 */
	public static String impactMessage(Presentation p, SeatingPlan newPlan, Set<String> lockedSeats) {
		if (newPlan == null || Objects.equals(newPlan.getId(), p.getSeatingPlanId())) {
			return null;
		}
		Report shown = dialogReport(p, BookingSync.preview(p, newPlan, lockedSeats));
		return needsConfirmation(shown) ? message(p, newPlan, shown) : null;
	}

	private static String message(Presentation p, SeatingPlan newPlan, Report shown) {
		StringBuilder message = new StringBuilder();
		message.append(EntityLabels.presentation(p)).append(" bekommt die Bestuhlung \"")
				.append(EntityLabels.nameOrPlaceholder(newPlan.getName())).append("\":\n\n").append(summary(shown)).append('.');
		if (shown.unassigned() > 0) {
			message.append("\n\nBuchungen ohne Platz bleiben erhalten und können später einem Sitz zugewiesen werden.");
		}
		return message.toString();
	}

	private static boolean ask(Presentation p, SeatingPlan newPlan, Report shown) {
		return ConfirmDialog.confirm("Bestuhlung wechseln", message(p, newPlan, shown), "Wechseln", "button-primary", null);
	}
}
