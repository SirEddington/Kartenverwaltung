package de.eltviller_carneval_verein.karten.ui.event;

import java.util.Objects;
import java.util.function.Predicate;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.BookingSync.Report;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.ui.ConfirmDialog;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

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
	 * Wechselt die Vorstellung auf die Bestuhlung, bei Bedarf nach Rückfrage.
	 *
	 * @param newPlan die neue Bestuhlung; {@code null} hebt die Zuordnung auf, die Buchungen bleiben dabei unverändert
	 * @return {@code true}, wenn kein Wechsel nötig war oder der Wechsel angewendet wurde; {@code false} bei "Abbrechen"
	 */
	public static boolean confirm(Presentation p, SeatingPlan newPlan) {
		return confirm(p, newPlan, report -> ask(p, newPlan, report));
	}

	/** Wie {@link #confirm(Presentation, SeatingPlan)}, die Rückfrage kommt von {@code ask} (paketsichtbar für Tests). */
	static boolean confirm(Presentation p, SeatingPlan newPlan, Predicate<Report> ask) {
		String newId = newPlan != null ? newPlan.getId() : null;
		if (Objects.equals(newId, p.getSeatingPlanId())) {
			return true;
		}
		if (newPlan == null) {
			BookingSync.switchPlan(p, null);
			return true;
		}
		Report shown = dialogReport(p, BookingSync.preview(p, newPlan));
		if (needsConfirmation(shown) && !ask.test(shown)) {
			return false;
		}
		BookingSync.switchPlan(p, newPlan);
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

	private static boolean ask(Presentation p, SeatingPlan newPlan, Report shown) {
		StringBuilder message = new StringBuilder();
		message.append(EntityLabels.presentation(p)).append(" bekommt die Bestuhlung \"")
				.append(EntityLabels.nameOrPlaceholder(newPlan.getName())).append("\":\n\n").append(summary(shown)).append('.');
		if (shown.unassigned() > 0) {
			message.append("\n\nBuchungen ohne Platz bleiben erhalten und können später einem Sitz zugewiesen werden.");
		}
		return ConfirmDialog.confirm("Bestuhlung wechseln", message.toString(), "Wechseln", "button-primary", null);
	}
}
