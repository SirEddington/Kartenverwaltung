package de.eltviller_carneval_verein.karten.ui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.CashRegister;
import de.eltviller_carneval_verein.karten.model.CashRegisters;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.LockedCashRegister;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.ui.event.CashRegisterSection.Row;

class CashRegisterSectionTest {

	private static final BiPredicate<String, String> NONE_UNAVAILABLE = (eventId, presentationId) -> false;

	private final Event event = new Event();
	private final Presentation first = event.addPresentation("1. Sitzung");
	private final Presentation second = event.addPresentation("2. Sitzung");

	private List<Row> rows(Map<String, LockedCashRegister> locked) {
		return CashRegisterSection.rows(event, locked, NONE_UNAVAILABLE, false, true);
	}

	private List<Row> rows() {
		return rows(Map.of());
	}

	private static List<String> names(List<Row> rows) {
		return rows.stream().map(Row::name).toList();
	}

	private static List<String> scopes(List<Row> rows) {
		return rows.stream().map(Row::scope).toList();
	}

	private CashRegister orphan(String id, int cents) {
		CashRegister register = new CashRegister(id);
		register.setActualCashAmount(cents);
		event.putCashRegister(register);
		return register;
	}

	private static Row row(List<Row> rows, String id) {
		return rows.stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
	}

	@Test
	void rowsPerPresentationByDefault() {
		List<Row> rows = rows();

		assertEquals(List.of(first.getId(), second.getId()), rows.stream().map(Row::id).toList());
		assertEquals(List.of("Kasse 1. Sitzung", "Kasse 2. Sitzung"), names(rows));
		assertEquals(List.of("1. Sitzung", "2. Sitzung"), scopes(rows));
		assertTrue(rows.get(0).nameEditable());
		assertFalse(rows.get(0).scopeEditable());
		assertFalse(rows.get(0).deletable());
	}

	@Test
	void sharedRowFirst() {
		CashRegister free = CashRegisters.addFree(event);
		CashRegisters.selectShared(event, true);
		first.setActualCashAmount(500);

		List<Row> rows = rows();

		assertEquals(List.of(CashRegister.SHARED_ID, first.getId(), free.getId()), rows.stream().map(Row::id).toList());
		assertEquals(List.of("Gemeinsame Kasse", "Kasse 1. Sitzung", "Neue Kasse"), names(rows));
		assertEquals(List.of("alle Vorstellungen", "1. Sitzung", "alle Vorstellungen"), scopes(rows));
	}

	@Test
	void orphanScopeDeletedVsUnavailable() {
		orphan("geloescht", 100).setName("Kasse Generalprobe");
		orphan("gesperrt", 200).setName("Kasse Matinee");

		List<Row> rows = CashRegisterSection.rows(event, Map.of(), (eventId, presentationId) -> "gesperrt".equals(presentationId),
				false, true);

		assertEquals("– (Vorstellung gelöscht)", row(rows, "geloescht").scope());
		assertEquals("– (Vorstellung nicht verfügbar)", row(rows, "gesperrt").scope());
		assertEquals("Kasse Generalprobe", row(rows, "geloescht").name());
	}

	@Test
	void lockedReadableRowMarkedAndNotEditable() {
		CashRegister free = CashRegisters.addFree(event);
		free.setName("Vorverkauf Rathaus");

		List<Row> rows = rows(Map.of(free.getId(), new LockedCashRegister(free.getId(), Status.LOCKED_FORMAT, true),
				first.getId(), new LockedCashRegister(first.getId(), Status.WAITING, true)));

		Row locked = row(rows, free.getId());
		assertEquals("Vorverkauf Rathaus (gesperrt)", locked.name());
		assertEquals("alle Vorstellungen", locked.scope());
		assertFalse(locked.nameEditable());
		assertFalse(locked.scopeEditable());
		assertFalse(locked.deletable());
		assertEquals("Kasse 1. Sitzung (gesperrt)", row(rows, first.getId()).name());
		assertFalse(row(rows, first.getId()).nameEditable());
		assertTrue(row(rows, second.getId()).nameEditable());
	}

	@Test
	void lockedUnreadableReplacesRow() {
		String freeId = CashRegister.newFreeId();
		List<Row> rows = rows(Map.of(first.getId(), new LockedCashRegister(first.getId(), Status.LOCKED_DAMAGED, false),
				freeId, new LockedCashRegister(freeId, Status.LOCKED_FORMAT, false),
				"alt", new LockedCashRegister("alt", Status.LOCKED_FORMAT, false)));

		assertEquals(List.of(first.getId(), second.getId(), freeId, "alt"), rows.stream().map(Row::id).toList());
		assertEquals("Kasse 1. Sitzung (gesperrt)", row(rows, first.getId()).name());
		assertEquals("1. Sitzung", row(rows, first.getId()).scope());
		assertEquals("Gesperrte Kasse", row(rows, freeId).name());
		assertEquals("–", row(rows, freeId).scope());
		assertEquals("Kasse ohne Vorstellung (gesperrt)", row(rows, "alt").name());
		assertEquals("– (Vorstellung gelöscht)", row(rows, "alt").scope());
		rows.stream().filter(row -> !row.id().equals(second.getId())).forEach(row -> {
			assertFalse(row.nameEditable());
			assertFalse(row.deletable());
		});
	}

	@Test
	void lockedUnreadableSharedCountsAsSelected() {
		Map<String, LockedCashRegister> locked = Map.of(CashRegister.SHARED_ID,
				new LockedCashRegister(CashRegister.SHARED_ID, Status.LOCKED_DAMAGED, false));
		second.setActualCashAmount(700);

		List<Row> rows = rows(locked);

		assertEquals(List.of(CashRegister.SHARED_ID, second.getId()), rows.stream().map(Row::id).toList());
		assertEquals("Gemeinsame Kasse (gesperrt)", rows.get(0).name());
		assertEquals("alle Vorstellungen", rows.get(0).scope());
		assertTrue(CashRegisterSection.sharedSelected(event, locked));
		assertFalse(CashRegisterSection.sharedSelected(event, Map.of()));
		assertFalse(CashRegisterSection.switchEnabled(locked, false, true));
		assertTrue(CashRegisterSection.switchEnabled(Map.of(), false, true));
	}

	@Test
	void lockedUnreadableOrphanAppearsWithoutData() {
		List<Row> rows = CashRegisterSection.rows(event,
				Map.of("weg", new LockedCashRegister("weg", Status.LOCKED_FORMAT, false)), (eventId, id) -> "weg".equals(id), false, true);

		assertEquals("Kasse ohne Vorstellung (gesperrt)", row(rows, "weg").name());
		assertEquals("– (Vorstellung nicht verfügbar)", row(rows, "weg").scope());
	}

	@Test
	void pendingMakesEverythingReadOnly() {
		CashRegisters.addFree(event);

		List<Row> rows = CashRegisterSection.rows(event, Map.of(), NONE_UNAVAILABLE, true, true);

		assertEquals(3, rows.size());
		rows.forEach(row -> {
			assertFalse(row.nameEditable());
			assertFalse(row.scopeEditable());
			assertFalse(row.deletable());
		});
		assertFalse(CashRegisterSection.switchEnabled(Map.of(), true, true));
		assertFalse(CashRegisterSection.switchEnabled(Map.of(), false, false));
		assertEquals("Die Kassen dieses Events werden noch abgeglichen.", CashRegisterSection.PENDING);
	}

	@Test
	void onlyFreeRegistersDeletable() {
		CashRegisters.selectShared(event, true);
		first.setActualCashAmount(100);
		CashRegister free = CashRegisters.addFree(event);
		orphan("weg", 300);

		List<Row> rows = rows();

		assertEquals(List.of(free.getId()), rows.stream().filter(Row::deletable).map(Row::id).toList());
		assertEquals(List.of(free.getId()), rows.stream().filter(Row::scopeEditable).map(Row::id).toList());
		assertTrue(rows.stream().allMatch(Row::nameEditable));
		List<Row> viewRows = CashRegisterSection.rows(event, Map.of(), NONE_UNAVAILABLE, false, false);
		assertTrue(viewRows.stream().noneMatch(row -> row.deletable() || row.nameEditable() || row.scopeEditable()));
	}

	@Test
	void scopeListsSelectedNamesAndSkipsMissing() {
		CashRegister free = CashRegisters.addFree(event);
		free.setAllPresentations(false);
		free.setPresentationIds(List.of(second.getId(), "geloescht", first.getId()));

		assertEquals("1. Sitzung, 2. Sitzung", row(rows(), free.getId()).scope());

		free.setPresentationIds(List.of("geloescht"));
		assertEquals("–", row(rows(), free.getId()).scope());
	}

	@Test
	void scopeChangeDropsMissingPresentations() {
		CashRegister free = CashRegisters.addFree(event);
		free.setPresentationIds(List.of("geloescht", second.getId()));

		CashRegisterSection.selectPresentation(event, free.getId(), first.getId(), true);

		assertFalse(free.isAllPresentations());
		assertEquals(List.of(first.getId(), second.getId()), free.getPresentationIds());

		CashRegisterSection.selectPresentation(event, free.getId(), second.getId(), false);
		assertEquals(List.of(first.getId()), free.getPresentationIds());

		free.getPresentationIds().add("weg");
		CashRegisterSection.selectAllPresentations(event, free.getId(), true);
		assertTrue(free.isAllPresentations());
		assertEquals(List.of(first.getId()), free.getPresentationIds());
	}

	@Test
	void renameSetsNameAndEmptyRestoresDefault() {
		assertTrue(CashRegisterSection.rename(event, first.getId(), "Abendkasse"));
		assertEquals("Abendkasse", event.findCashRegister(first.getId()).getName());

		assertTrue(CashRegisterSection.rename(event, first.getId(), " "));
		assertNull(event.findCashRegister(first.getId()).getName());
	}

	@Test
	void renameToShownNameChangesNothing() {
		assertFalse(CashRegisterSection.rename(event, first.getId(), "Kasse 1. Sitzung"));
		assertFalse(CashRegisterSection.rename(event, second.getId(), ""));
		assertNull(event.findCashRegister(first.getId()));
		assertNull(event.findCashRegister(second.getId()));
	}

	@Test
	void modelOfRowIsRegisterOrPresentation() {
		CashRegister free = CashRegisters.addFree(event);

		assertSame(free, CashRegisterSection.modelOf(event, free.getId()));
		assertSame(first, CashRegisterSection.modelOf(event, first.getId()));
		assertNull(CashRegisterSection.modelOf(event, "unbekannt"));
		assertNull(CashRegisterSection.modelOf(null, first.getId()));
	}

	@Test
	void refusalNamesLockOrPending() {
		assertNull(CashRegisterSection.refusal("Vorverkauf", null, false));
		assertEquals(CashRegisterSection.PENDING, CashRegisterSection.refusal("Vorverkauf", null, true));
		assertTrue(CashRegisterSection.refusal("Vorverkauf", new LockedCashRegister("_x", Status.LOCKED_FORMAT, true), false)
				.startsWith("SYS-009: Kasse \"Vorverkauf\""));
		assertTrue(CashRegisterSection.refusal("Vorverkauf", new LockedCashRegister("_x", Status.LOCKED_DAMAGED, true), false)
				.startsWith("SYS-008: Kasse \"Vorverkauf\""));
		assertEquals("Kasse \"Vorverkauf\" wartet noch auf eine Datei aus dem gemeinsamen Ordner. Ändern ist erst möglich, "
				+ "wenn sie angekommen ist.",
				CashRegisterSection.refusal("Vorverkauf", new LockedCashRegister("_x", Status.WAITING, true), false));
	}

	@Test
	void footerTexts() {
		assertEquals("Die gemeinsame Kasse hat einen Ist-Betrag oder Kommentar und bleibt bestehen.", CashRegisterSection.SHARED_KEPT);
		assertEquals("Die Kasse \"Vorverkauf\" hat einen Ist-Betrag oder Kommentar und kann nicht gelöscht werden.",
				CashRegisterSection.deleteRefused("Vorverkauf"));
		assertEquals("Soll die Kasse \"Vorverkauf\" wirklich gelöscht werden?", CashRegisterSection.deleteConfirm("Vorverkauf"));
	}
}
