package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.CashRegisters.CashState;
import de.eltviller_carneval_verein.karten.model.CashRegisters.VisibleCashRegister;

class CashRegistersTest {

	private final Event event = new Event();

	private Presentation presentation(String name) {
		return event.addPresentation(name);
	}

	private static List<String> ids(List<VisibleCashRegister> lines) {
		return lines.stream().map(VisibleCashRegister::id).toList();
	}

	@Test
	void effectiveUsesOldFieldsWithoutObject() {
		Presentation p = presentation("Sitzung 1");
		p.setActualCashAmount(125000);
		p.setCashDifferenceComment("k");

		assertEquals(new CashState(125000, "k"), CashRegisters.effective(event, p));
	}

	@Test
	void effectiveUsesObjectWhenPresent() {
		Presentation p = presentation("Sitzung 1");
		p.setActualCashAmount(125000);
		CashRegister register = new CashRegister(p.getId());
		register.setActualCashAmount(700);
		register.setCashDifferenceComment("neu");
		event.putCashRegister(register);

		assertEquals(new CashState(700, "neu"), CashRegisters.effective(event, p));
	}

	@Test
	void cashStateIsEmptyForZeroAndBlankComment() {
		assertTrue(new CashState(0, null).isEmpty());
		assertTrue(new CashState(0, "").isEmpty());
		assertFalse(new CashState(1, null).isEmpty());
		assertFalse(new CashState(0, "x").isEmpty());
	}

	@Test
	void forEditMovesOldFieldsIntoNewObject() {
		Presentation p = presentation("Sitzung 1");
		p.setActualCashAmount(125000);
		p.setCashDifferenceComment("k");

		CashRegister register = CashRegisters.forEdit(event, p);

		assertEquals(0, p.getActualCashAmount());
		assertNull(p.getCashDifferenceComment());
		assertEquals(125000, register.getActualCashAmount());
		assertEquals("k", register.getCashDifferenceComment());
		assertSame(register, event.findCashRegister(p.getId()));
		assertSame(register, CashRegisters.forEdit(event, p));
	}

	@Test
	void forEditKeepsOldFieldsOfExistingObject() {
		Presentation p = presentation("Sitzung 1");
		event.putCashRegister(new CashRegister(p.getId()));
		p.setActualCashAmount(300);

		CashRegisters.forEdit(event, p);

		assertEquals(300, p.getActualCashAmount());
	}

	@Test
	void setActualCashClearsOldFieldsAlsoForExistingObject() {
		Presentation p = presentation("Sitzung 1");
		event.putCashRegister(new CashRegister(p.getId()));
		p.setActualCashAmount(300);
		p.setCashDifferenceComment("alt");

		CashRegisters.setActualCash(event, p, 500);

		assertEquals(500, event.findCashRegister(p.getId()).getActualCashAmount());
		assertEquals(0, p.getActualCashAmount());
		assertNull(p.getCashDifferenceComment());
	}

	@Test
	void setCommentClearsOldFields() {
		Presentation p = presentation("Sitzung 1");
		event.putCashRegister(new CashRegister(p.getId()));
		p.setActualCashAmount(300);

		CashRegisters.setComment(event, p, "neu");

		assertEquals("neu", event.findCashRegister(p.getId()).getCashDifferenceComment());
		assertEquals(0, p.getActualCashAmount());
	}

	@Test
	void setNameTurnsBlankIntoNullAndGoesThroughForEdit() {
		Presentation p = presentation("Sitzung 1");
		p.setActualCashAmount(900);

		CashRegisters.setName(event, p.getId(), "Saal");
		assertEquals("Saal", event.findCashRegister(p.getId()).getName());
		assertEquals(900, event.findCashRegister(p.getId()).getActualCashAmount());
		assertEquals(0, p.getActualCashAmount());

		CashRegisters.setName(event, p.getId(), "  ");
		assertNull(event.findCashRegister(p.getId()).getName());
	}

	@Test
	void setNameOfUnknownIdDoesNothing() {
		CashRegisters.setName(event, "gibtsnicht", "x");

		assertTrue(event.getCashRegisters().isEmpty());
	}

	@Test
	void hasOwnDataOfPresentationRegister() {
		Presentation p = presentation("Sitzung 1");
		CashRegister register = new CashRegister(p.getId());
		assertFalse(CashRegisters.hasOwnData(event, register));

		register.setName("Name");
		assertTrue(CashRegisters.hasOwnData(event, register));

		CashRegister orphan = new CashRegister("fehlt");
		orphan.setName("Name");
		assertFalse(CashRegisters.hasOwnData(event, orphan));

		CashRegister withAmount = new CashRegister("fehlt");
		withAmount.setActualCashAmount(1);
		assertTrue(CashRegisters.hasOwnData(event, withAmount));

		CashRegister withComment = new CashRegister(p.getId());
		withComment.setCashDifferenceComment("x");
		assertTrue(CashRegisters.hasOwnData(event, withComment));
	}

	@Test
	void sharedAndFreeRegistersAlwaysHaveOwnData() {
		assertTrue(CashRegisters.hasOwnData(event, new CashRegister(CashRegister.SHARED_ID)));
		assertTrue(CashRegisters.hasOwnData(event, new CashRegister()));
	}

	@Test
	void visibleOrderWithoutShared() {
		Presentation a = presentation("Zweite");
		Presentation b = presentation("Erste");
		CashRegister freeB = CashRegisters.addFree(event);
		freeB.setName("B-Kasse");
		CashRegister freeA = CashRegisters.addFree(event);
		freeA.setName("A-Kasse");
		CashRegister orphanB = new CashRegister("weg-b");
		orphanB.setName("Y");
		orphanB.setActualCashAmount(5);
		CashRegister orphanA = new CashRegister("weg-a");
		orphanA.setName("X");
		orphanA.setCashDifferenceComment("k");
		CashRegister orphanEmpty = new CashRegister("weg-leer");
		orphanEmpty.setName("Z");
		event.putCashRegister(orphanB);
		event.putCashRegister(orphanA);
		event.putCashRegister(orphanEmpty);

		List<VisibleCashRegister> lines = CashRegisters.visible(event);

		assertEquals(List.of(a.getId(), b.getId(), freeA.getId(), freeB.getId(), "weg-a", "weg-b"), ids(lines));
		assertFalse(lines.get(0).orphan());
		assertNull(lines.get(0).register());
		assertSame(a, lines.get(0).presentation());
		assertEquals("Kasse Zweite", lines.get(0).displayName());
		assertTrue(lines.get(4).orphan());
		assertNull(lines.get(4).presentation());
	}

	@Test
	void visibleWithSharedHidesEmptyPresentationRegisters() {
		Presentation a = presentation("A");
		Presentation b = presentation("B");
		Presentation c = presentation("C");
		Presentation d = presentation("D");
		CashRegisters.selectShared(event, true);
		b.setActualCashAmount(10);
		c.setCashDifferenceComment("k");
		CashRegisters.setName(event, d.getId(), "Eigener");

		List<VisibleCashRegister> lines = CashRegisters.visible(event);

		assertEquals(List.of(CashRegister.SHARED_ID, b.getId(), c.getId(), d.getId()), ids(lines));
		assertFalse(ids(lines).contains(a.getId()));
		assertEquals(CashRegister.Kind.SHARED, lines.get(0).kind());
		assertEquals("Gemeinsame Kasse", lines.get(0).displayName());
	}

	@Test
	void visibleKeepsCountedPresentationRegisterWhenShared() {
		Presentation a = presentation("A");
		CashRegisters.setActualCash(event, a, 4200);
		CashRegisters.selectShared(event, true);

		List<VisibleCashRegister> lines = CashRegisters.visible(event);

		assertEquals(List.of(CashRegister.SHARED_ID, a.getId()), ids(lines));
		assertEquals(4200, lines.get(1).cash().actualCashAmount());
	}

	@Test
	void defaultNameFollowsRenamedPresentation() {
		Presentation p = presentation("Sitzung 1");
		assertEquals("Kasse Sitzung 1", CashRegisters.visible(event).get(0).displayName());

		p.setName("Sitzung A");

		assertEquals("Kasse Sitzung A", CashRegisters.visible(event).get(0).displayName());
		assertTrue(event.getCashRegisters().isEmpty());
	}

	@Test
	void displayNameOfOrphanWithoutNameAndOfFreeAndShared() {
		assertEquals("Kasse ohne Vorstellung", CashRegisters.displayName(event, new CashRegister("weg")));
		assertEquals("Gemeinsame Kasse", CashRegisters.displayName(event, new CashRegister(CashRegister.SHARED_ID)));
		CashRegister named = new CashRegister(CashRegister.SHARED_ID);
		named.setName("Haupt");
		assertEquals("Haupt", CashRegisters.displayName(event, named));
	}

	@Test
	void addFreeNumbersNames() {
		CashRegister first = CashRegisters.addFree(event);
		CashRegister second = CashRegisters.addFree(event);
		first.setName(" neue KASSE ");
		CashRegister third = CashRegisters.addFree(event);

		assertEquals("Neue Kasse 2", second.getName());
		assertEquals("Neue Kasse 3", third.getName());
		assertTrue(first.isAllPresentations());
		assertEquals(CashRegister.Kind.FREE, first.kind());
		assertEquals(3, event.getCashRegisters().size());
	}

	@Test
	void addFreeNamesFirstRegisterNeueKasse() {
		assertEquals("Neue Kasse", CashRegisters.addFree(event).getName());
	}

	@Test
	void removeFreeRefusesWithAmount() {
		CashRegister free = CashRegisters.addFree(event);
		free.setActualCashAmount(100);

		assertFalse(CashRegisters.removeFree(event, free.getId()));
		assertNotNull(event.findCashRegister(free.getId()));

		free.setActualCashAmount(0);
		free.setCashDifferenceComment("k");
		assertFalse(CashRegisters.removeFree(event, free.getId()));

		free.setCashDifferenceComment(null);
		assertTrue(CashRegisters.removeFree(event, free.getId()));
		assertNull(event.findCashRegister(free.getId()));
	}

	@Test
	void removeFreeIgnoresNonFreeRegisters() {
		CashRegisters.selectShared(event, true);

		assertFalse(CashRegisters.removeFree(event, CashRegister.SHARED_ID));
		assertTrue(CashRegisters.sharedSelected(event));
	}

	@Test
	void selectSharedOffRefusedWithComment() {
		assertFalse(CashRegisters.sharedSelected(event));
		assertTrue(CashRegisters.selectShared(event, true));
		assertTrue(CashRegisters.sharedSelected(event));
		event.findCashRegister(CashRegister.SHARED_ID).setCashDifferenceComment("k");

		assertFalse(CashRegisters.selectShared(event, false));
		assertTrue(CashRegisters.sharedSelected(event));

		event.findCashRegister(CashRegister.SHARED_ID).setCashDifferenceComment(null);
		assertTrue(CashRegisters.selectShared(event, false));
		assertFalse(CashRegisters.sharedSelected(event));
	}

	@Test
	void selectSharedOffRefusedWithAmount() {
		CashRegisters.selectShared(event, true);
		event.findCashRegister(CashRegister.SHARED_ID).setActualCashAmount(1);

		assertFalse(CashRegisters.selectShared(event, false));
	}

	@Test
	void presentationRemovedKeepsCountedRegisterAsOrphanWithName() {
		Presentation p = presentation("Sitzung 1");
		p.setActualCashAmount(5000);

		event.deletePresentation(p);

		CashRegister register = event.findCashRegister(p.getId());
		assertNotNull(register);
		assertEquals("Kasse Sitzung 1", register.getName());
		assertEquals(5000, register.getActualCashAmount());
		List<VisibleCashRegister> lines = CashRegisters.visible(event);
		assertEquals(1, lines.size());
		assertTrue(lines.get(0).orphan());
		assertEquals("Kasse Sitzung 1", lines.get(0).displayName());
	}

	@Test
	void presentationRemovedKeepsOwnName() {
		Presentation p = presentation("Sitzung 1");
		CashRegisters.setActualCash(event, p, 100);
		CashRegisters.setName(event, p.getId(), "Saal");

		event.deletePresentation(p);

		assertEquals("Saal", event.findCashRegister(p.getId()).getName());
	}

	@Test
	void presentationRemovedDropsEmptyObject() {
		Presentation p = presentation("Sitzung 1");
		CashRegisters.setName(event, p.getId(), "Saal");
		assertNotNull(event.findCashRegister(p.getId()));

		event.deletePresentation(p);

		assertNull(event.findCashRegister(p.getId()));
		assertTrue(CashRegisters.visible(event).isEmpty());
	}

	@Test
	void secondAmountReportedOnlyWithObject() {
		Presentation p = presentation("Sitzung 1");
		p.setActualCashAmount(300);
		assertTrue(CashRegisters.secondAmount(event, p).isEmpty());

		event.putCashRegister(new CashRegister(p.getId()));
		assertEquals(new CashState(300, null), CashRegisters.secondAmount(event, p).orElseThrow());

		p.setActualCashAmount(0);
		assertTrue(CashRegisters.secondAmount(event, p).isEmpty());
		p.setCashDifferenceComment("k");
		assertTrue(CashRegisters.secondAmount(event, p).isPresent());
	}

	@Test
	void discardSecondAmountClearsOldFields() {
		Presentation p = presentation("Sitzung 1");
		p.setActualCashAmount(300);
		p.setCashDifferenceComment("k");

		CashRegisters.discardSecondAmount(p);

		assertEquals(0, p.getActualCashAmount());
		assertNull(p.getCashDifferenceComment());
	}

	@Test
	void freeRegisterWithoutNameShowsPlaceholder() {
		CashRegister free = CashRegisters.addFree(event);
		CashRegisters.setName(event, free.getId(), "");

		assertNull(free.getName());
		assertEquals("(ohne Name)", CashRegisters.displayName(event, free));
		assertEquals("(ohne Name)", CashRegisters.visible(event).get(0).displayName());
	}

	@Test
	void presentationWithoutNameGetsPlaceholderInDefaultName() {
		Presentation p = presentation(null);

		assertEquals("Kasse (ohne Name)", CashRegisters.visible(event).get(0).displayName());

		p.setActualCashAmount(500);
		CashRegisters.presentationRemoved(event, p);
		assertEquals("Kasse (ohne Name)", event.findCashRegister(p.getId()).getName());
	}

	@Test
	void setCommentBlankBecomesNull() {
		Presentation p = presentation("Sitzung 1");
		CashRegisters.setComment(event, p, "k");
		CashRegisters.setComment(event, p, "  ");

		assertNull(event.findCashRegister(p.getId()).getCashDifferenceComment());
		CashRegisters.setComment(event, p, "");
		assertNull(event.findCashRegister(p.getId()).getCashDifferenceComment());
	}

	@Test
	void setPresentationIdsNullBecomesEmptyList() {
		CashRegister register = new CashRegister();
		register.setPresentationIds(null);

		assertTrue(register.getPresentationIds().isEmpty());
	}
}
