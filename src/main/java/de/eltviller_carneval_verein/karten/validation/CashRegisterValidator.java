package de.eltviller_carneval_verein.karten.validation;

import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.model.CashRegister;
import de.eltviller_carneval_verein.karten.model.CashRegisters;
import de.eltviller_carneval_verein.karten.model.CashRegisters.CashState;
import de.eltviller_carneval_verein.karten.model.CashRegisters.VisibleCashRegister;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;

/**
 * Plausibilitätsprüfungen für die Kassen eines Events (Kürzel KAS, siehe {@link CashRegisterIssue}). Die Namensgleichheit
 * ({@code KAS-002}) ist ein Vergleich unter Geschwistern und steht im {@link EventValidator}. Aufbau wie bei
 * {@link SeatValidator}.
 */
public final class CashRegisterValidator {

	private static final Logger LOG = Logger.getLogger(CashRegisterValidator.class.getName());

	private static final Function<String, Set<String>> NO_UNREADABLE = eventId -> Set.of();

	/** Siehe {@link #useUnreadableCashRegisters}. */
	private static volatile Function<String, Set<String>> unreadable = NO_UNREADABLE;

	/** Größter erlaubter Ist-Betrag in Cent: 1.000.000 € (wie bisher die Grenze des Spinners). */
	static final long MAX_CASH_CENTS = 100_000_000L;

	private final Event event;
	private final Set<String> unreadableIds;
	private final ValidationResult result = ValidationResult.ok();

	private CashRegisterValidator(Event event) {
		this.event = event;
		this.unreadableIds = unreadableIdsOf(event);
	}

	public static ValidationResult validate(Event event) {
		return new CashRegisterValidator(event).run();
	}

	/**
	 * Setzt die Abfrage nach den Kennungen der Kassen eines Events, deren Einheit gesperrt ist oder wartet und die keinen
	 * lesbaren Stand haben (KARV-144; in der App {@code StoreEventRepository.lockedCashRegisters}). Solche Kassen nehmen
	 * an {@code KAS-004} und {@code KAS-005} nicht teil. Ohne Einrichtung, nach {@code null} oder wenn die Abfrage
	 * scheitert, hat jede Kasse einen lesbaren Stand.
	 */
	public static void useUnreadableCashRegisters(Function<String, Set<String>> idsOfEvent) {
		unreadable = idsOfEvent != null ? idsOfEvent : NO_UNREADABLE;
	}

	private static Set<String> unreadableIdsOf(Event event) {
		try {
			Set<String> ids = unreadable.apply(event.getId());
			return ids != null ? ids : Set.of();
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "Keine Auskunft über die Kassen des Events " + event.getId(), e);
			return Set.of();
		}
	}

	private ValidationResult run() {
		for (VisibleCashRegister line : CashRegisters.visible(event)) {
			if (line.kind() == CashRegister.Kind.FREE) {
				validateFreeRegister(line);
			}
			if (!unreadableIds.contains(line.id())) {
				validateCash(line);
			}
		}
		validateSecondAmounts();
		return result;
	}

	private void validateFreeRegister(VisibleCashRegister line) {
		CashRegister register = line.register();
		String name = register.getName();
		if (name == null || name.isBlank()) {
			result.add(CashRegisterIssue.NAME_EMPTY.toIssue());
		}
		if (!register.isAllPresentations() && register.getPresentationIds().stream().noneMatch(id -> event.findPresentation(id) != null)) {
			result.add(CashRegisterIssue.NO_PRESENTATION.toIssue(EntityLabels.cashRegister(line.displayName())));
		}
	}

	private void validateCash(VisibleCashRegister line) {
		RangeCheck.checkCents(result, CashRegisterIssue.CASH_OUT_OF_RANGE, "Ist-Betrag", EntityLabels.cashRegister(line.displayName()),
				line.cash().actualCashAmount(), 0, MAX_CASH_CENTS);
	}

	/** Neben dem Kassen-Objekt steht in den alten Feldern der Vorstellung noch ein Stand ({@code KAS-005}). */
	private void validateSecondAmounts() {
		for (Presentation presentation : event.getPresentations()) {
			if (unreadableIds.contains(presentation.getId())) {
				continue;
			}
			CashRegisters.secondAmount(event, presentation).ifPresent(old -> addSecondAmount(presentation, old));
		}
	}

	private void addSecondAmount(Presentation presentation, CashState old) {
		String label = EntityLabels.cashRegister(CashRegisters.displayName(event, event.findCashRegister(presentation.getId())));
		result.add(CashRegisterIssue.SECOND_AMOUNT.toIssue(label, MoneyFormat.formatCents(old.actualCashAmount())));
	}
}
