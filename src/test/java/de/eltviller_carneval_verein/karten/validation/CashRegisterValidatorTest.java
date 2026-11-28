package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.CashRegister;
import de.eltviller_carneval_verein.karten.model.CashRegisters;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;

class CashRegisterValidatorTest {

	@AfterEach
	void resetHook() {
		CashRegisterValidator.useUnreadableCashRegisters(null);
	}

	private static Presentation presentation(Event event, String name) {
		Presentation presentation = event.addPresentation(name);
		presentation.setDate(LocalDate.of(2027, 2, 12));
		presentation.setTime(LocalTime.of(19, 11));
		presentation.setHallId("halle-1");
		presentation.setSeatingPlanId("plan-1");
		return presentation;
	}

	private static Event event() {
		Event event = new Event();
		event.setName("Kampagne");
		return event;
	}

	private static List<String> codes(ValidationResult result) {
		return result.getIssues().stream().map(ValidationIssue::code).toList();
	}

	private static ValidationIssue only(ValidationResult result) {
		assertEquals(1, result.getIssues().size(), "Genau eine Meldung erwartet: " + result.getIssues());
		return result.getIssues().get(0);
	}

	@Test
	void cleanEventHasNoIssues() {
		Event event = event();
		presentation(event, "Sitzung");
		CashRegisters.addFree(event);

		assertTrue(EventValidator.validate(event).isEmpty(), EventValidator.validate(event).getIssues().toString());
	}

	@Test
	void freeRegisterWithoutNameIsError() {
		Event event = event();
		CashRegister free = CashRegisters.addFree(event);
		free.setName("  ");

		ValidationIssue issue = only(EventValidator.validate(event));
		assertEquals("KAS-001", issue.code());
		assertEquals(Severity.ERROR, issue.severity());
		assertEquals("Der Name einer Kasse darf nicht leer sein.", issue.message());
	}

	@Test
	void duplicateFromTwoSameNamedPresentationsIsWarningOnly() {
		Event event = event();
		presentation(event, "Sitzung");
		presentation(event, "Sitzung 2").setName("Sitzung");

		ValidationResult result = EventValidator.validate(event);
		assertTrue(codes(result).contains("KAS-002"), codes(result).toString());
		ValidationIssue issue = result.getIssues().stream().filter(i -> i.code().equals("KAS-002")).findFirst().orElseThrow();
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Der Kassenname Kasse Sitzung kommt im Event mehrfach vor.", issue.message());
		assertTrue(result.isValid());
	}

	@Test
	void duplicateIgnoresCaseAndEdgeSpaces() {
		Event event = event();
		CashRegisters.addFree(event).setName("Bar");
		CashRegisters.addFree(event).setName(" bar ");

		assertTrue(codes(EventValidator.validate(event)).contains("KAS-002"));
	}

	@Test
	void freeRegisterForNoPresentationIsWarning() {
		Event event = event();
		presentation(event, "Sitzung");
		CashRegister free = CashRegisters.addFree(event);
		free.setAllPresentations(false);
		free.setPresentationIds(List.of("gibt-es-nicht"));

		ValidationIssue issue = only(EventValidator.validate(event));
		assertEquals("KAS-003", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Kasse \"Neue Kasse\" gilt für keine Vorstellung.", issue.message());
	}

	@Test
	void freeRegisterForExistingPresentationIsFine() {
		Event event = event();
		Presentation sitzung = presentation(event, "Sitzung");
		CashRegister free = CashRegisters.addFree(event);
		free.setAllPresentations(false);
		free.setPresentationIds(List.of(sitzung.getId()));

		assertTrue(EventValidator.validate(event).isEmpty());
	}

	@Test
	void orphansSkipDuplicateAndScope() {
		Event event = event();
		Presentation sitzung = presentation(event, "Sitzung");
		sitzung.setActualCashAmount(500);
		event.deletePresentation(sitzung);
		Presentation again = presentation(event, "Sitzung");
		again.setActualCashAmount(700);
		// Die verwaiste Kasse heißt wie die Kasse der neuen Vorstellung, ist aber sichtbar und verwaist
		assertTrue(CashRegisters.visible(event).stream().anyMatch(l -> l.orphan()));

		List<String> codes = codes(EventValidator.validate(event));
		assertTrue(!codes.contains("KAS-002") && !codes.contains("KAS-003"), codes.toString());
	}

	@Test
	void cashAmountOutOfRangeIsError() {
		Event event = event();
		CashRegister free = CashRegisters.addFree(event);
		free.setName("Bar");
		free.setActualCashAmount(100_000_001);

		ValidationIssue issue = only(EventValidator.validate(event));
		assertEquals("KAS-004", issue.code());
		assertEquals(Severity.ERROR, issue.severity());
		assertEquals("Ist-Betrag von Kasse \"Bar\" liegt mit 1.000.000,01 € nicht zwischen 0,00 € und 1.000.000,00 €.", issue.message());

		free.setActualCashAmount(-1);
		assertEquals("KAS-004", only(EventValidator.validate(event)).code());
		free.setActualCashAmount(100_000_000);
		assertTrue(EventValidator.validate(event).isEmpty());
	}

	@Test
	void cashRangeUsesEffectiveState() {
		Event event = event();
		Presentation sitzung = presentation(event, "Sitzung");
		sitzung.setActualCashAmount(100_000_001);

		ValidationIssue issue = only(EventValidator.validate(event));
		assertEquals("KAS-004", issue.code());
		assertTrue(issue.message().startsWith("Ist-Betrag von Kasse \"Kasse Sitzung\" liegt mit 1.000.000,01 €"), issue.message());
	}

	@Test
	void secondAmountIsWarning() {
		Event event = event();
		Presentation sitzung = presentation(event, "Sitzung");
		CashRegisters.setActualCash(event, sitzung, 1000);
		sitzung.setActualCashAmount(2550);

		ValidationIssue issue = only(EventValidator.validate(event));
		assertEquals("KAS-005", issue.code());
		assertEquals(Severity.WARNING, issue.severity());
		assertEquals("Für Kasse \"Kasse Sitzung\" liegt aus einer älteren Programmversion ein zweiter Ist-Betrag von 25,50 € vor. Es gilt der Stand der Kasse.",
				issue.message());
	}

	@Test
	void unreadableRegisterSkipsRangeAndSecondAmount() {
		Event event = event();
		Presentation sitzung = presentation(event, "Sitzung");
		CashRegisters.setActualCash(event, sitzung, 1000);
		sitzung.setActualCashAmount(2550);
		Presentation fest = presentation(event, "Fest");
		fest.setActualCashAmount(100_000_001);
		assertEquals(List.of("KAS-004", "KAS-005"), codes(EventValidator.validate(event)).stream().sorted().toList());

		CashRegisterValidator.useUnreadableCashRegisters(eventId -> eventId.equals(event.getId()) ? Set.of(sitzung.getId(), fest.getId()) : Set.of());

		assertTrue(EventValidator.validate(event).isEmpty(), EventValidator.validate(event).getIssues().toString());
	}

	@Test
	void failingHookCountsAsReadable() {
		Event event = event();
		presentation(event, "Fest").setActualCashAmount(100_000_001);
		CashRegisterValidator.useUnreadableCashRegisters(eventId -> {
			throw new IllegalStateException("kaputt");
		});

		assertEquals("KAS-004", only(EventValidator.validate(event)).code());
	}
}
