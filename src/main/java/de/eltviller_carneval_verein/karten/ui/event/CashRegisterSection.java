package de.eltviller_carneval_verein.karten.ui.event;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import de.eltviller_carneval_verein.karten.model.CashRegister;
import de.eltviller_carneval_verein.karten.model.CashRegisters;
import de.eltviller_carneval_verein.karten.model.CashRegisters.VisibleCashRegister;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.LockedCashRegister;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import de.eltviller_carneval_verein.karten.validation.MessageTemplate;

/**
 * Die Logik des Abschnitts „Kassen" der Event-Verwaltung (KARV-144), ohne JavaFX: Zeilen aus den sichtbaren Kassen
 * ({@link CashRegisters#visible}), die Texte unter „gilt für", gesperrte Kassen und ihre Ersatzzeilen, was sich bearbeiten
 * lässt, und die Änderungen an Name und „gilt für". Die Oberfläche dazu ist {@link CashRegisterTable}. Die Regeln stehen
 * im Spec „Kassen am Event", Abschnitt „Oberfläche: Event-Verwaltung".
 */
final class CashRegisterSection {

	/** Die Fußzeile, wenn „je Vorstellung eine Kasse" die gemeinsame Kasse nicht entfernen kann. */
	static final String SHARED_KEPT = "Die gemeinsame Kasse hat einen Ist-Betrag oder Kommentar und bleibt bestehen.";
	/** Die Fußzeile, wenn eine freie Kasse nicht gelöscht werden kann; {@code &1} ist ihr Name. */
	static final String DELETE_REFUSED = "Die Kasse \"&1\" hat einen Ist-Betrag oder Kommentar und kann nicht gelöscht werden.";
	/** Die Rückfrage vor dem Löschen einer freien Kasse; {@code &1} ist ihr Name. */
	static final String DELETE_CONFIRM = "Soll die Kasse \"&1\" wirklich gelöscht werden?";
	/** Der Hinweis im Zustand „Kassen noch nicht abgeglichen". */
	static final String PENDING = StoreEventRepository.CASH_PENDING;

	static final String ALL_PRESENTATIONS = "alle Vorstellungen";
	static final String NO_SCOPE = "–";
	static final String PRESENTATION_DELETED = "– (Vorstellung gelöscht)";
	static final String PRESENTATION_UNAVAILABLE = "– (Vorstellung nicht verfügbar)";

	private static final String LOCKED_SUFFIX = " (gesperrt)";
	private static final String LOCKED_FREE = "Gesperrte Kasse";

	/**
	 * Eine Zeile der Tabelle.
	 *
	 * @param id            die Kennung der Kasse
	 * @param name          der angezeigte Name, bei einer gesperrten Kasse mit „(gesperrt)"
	 * @param scope         der Text unter „gilt für"
	 * @param nameEditable  ob sich der Name jetzt ändern lässt
	 * @param scopeEditable ob sich „gilt für" jetzt ändern lässt (nur freie Kassen)
	 * @param deletable     ob „Kasse löschen" angeboten wird (nur freie Kassen)
	 */
	record Row(String id, String name, String scope, boolean nameEditable, boolean scopeEditable, boolean deletable) {
	}

	private CashRegisterSection() {
	}

	/**
	 * Die Zeilen des Abschnitts: die sichtbaren Kassen ({@link CashRegisters#visible}) mit den gesperrten Kassen des
	 * Repositorys. Eine gesperrte Kasse mit lesbarem Stand trägt „(gesperrt)" hinter dem Namen; eine ohne lesbaren Stand
	 * ersetzt die Zeile, die es sonst für sie gäbe, und erscheint auch ohne sie (ihr Inhalt ist unbekannt und kann eine
	 * Zählung enthalten). Eine gesperrte gemeinsame Kasse gilt als gewählt, auch wenn sie im Modell fehlt. Im Anzeigemodus,
	 * im Zustand „Kassen noch nicht abgeglichen" und an gesperrten Kassen lässt sich nichts ändern.
	 *
	 * @param locked                  die gesperrten und wartenden Kassen je Kennung
	 *                                ({@link StoreEventRepository#lockedCashRegisters})
	 * @param presentationUnavailable ob die Vorstellung (Event-Kennung, Vorstellungs-Kennung) nur im Modell fehlt
	 *                                ({@link StoreEventRepository#presentationUnavailable})
	 * @param pending                 ob „Kassen noch nicht abgeglichen" gilt
	 * @param editMode                ob die Ansicht im Bearbeiten-Modus ist
	 */
	static List<Row> rows(Event event, Map<String, LockedCashRegister> locked, BiPredicate<String, String> presentationUnavailable,
			boolean pending, boolean editMode) {
		boolean editable = editMode && !pending;
		Map<String, VisibleCashRegister> visible = new LinkedHashMap<>();
		for (VisibleCashRegister line : CashRegisters.visible(event)) {
			visible.put(line.id(), line);
		}
		// Eine gesperrte gemeinsame Kasse, die im Modell fehlt, gilt als gewählt: Vorstellungskassen ohne Daten entfallen
		if (locked.containsKey(CashRegister.SHARED_ID) && !CashRegisters.sharedSelected(event)) {
			visible.values().removeIf(line -> line.kind() == CashRegister.Kind.PRESENTATION && !line.orphan() && line.cash().isEmpty()
					&& (line.register() == null || isBlank(line.register().getName())));
		}
		// Reihenfolge wie bei den sichtbaren Kassen: gemeinsame, je Vorstellung, freie, verwaiste; gesperrte Kassen ohne
		// lesbaren Stand und ohne Zeile jeweils am Ende ihrer Gruppe
		List<String> ids = new ArrayList<>();
		ids.add(CashRegister.SHARED_ID);
		event.getPresentations().forEach(presentation -> ids.add(presentation.getId()));
		visible.values().stream().filter(line -> line.kind() == CashRegister.Kind.FREE).forEach(line -> ids.add(line.id()));
		ids.addAll(unreadableWithoutLine(locked, visible, CashRegister.Kind.FREE));
		visible.values().stream().filter(VisibleCashRegister::orphan).forEach(line -> ids.add(line.id()));
		ids.addAll(unreadableWithoutLine(locked, visible, CashRegister.Kind.PRESENTATION));

		List<Row> rows = new ArrayList<>();
		Set<String> shown = new HashSet<>();
		for (String id : ids) {
			Row row = shown.contains(id) ? null : row(event, id, visible.get(id), locked.get(id), presentationUnavailable, editable);
			if (row != null) {
				rows.add(row);
				shown.add(id);
			}
		}
		return rows;
	}

	/** Die gesperrten Kassen der Art ohne lesbaren Stand und ohne sichtbare Zeile, nach Kennung. */
	private static List<String> unreadableWithoutLine(Map<String, LockedCashRegister> locked, Map<String, VisibleCashRegister> visible,
			CashRegister.Kind kind) {
		return locked.values().stream().filter(entry -> !entry.readable() && !visible.containsKey(entry.id()))
				.map(LockedCashRegister::id).filter(id -> CashRegister.kindOf(id) == kind).sorted().toList();
	}

	/** Die Zeile einer Kasse; {@code null}, wenn sie nicht sichtbar ist und keine Ersatzzeile braucht. */
	private static Row row(Event event, String id, VisibleCashRegister line, LockedCashRegister locked,
			BiPredicate<String, String> presentationUnavailable, boolean editable) {
		if (locked != null && !locked.readable()) {
			return replacementRow(event, id, presentationUnavailable);
		}
		if (line == null) {
			return null;
		}
		String scope = scopeText(event, line, line.orphan() && presentationUnavailable.test(event.getId(), id));
		if (locked != null) {
			return new Row(id, line.displayName() + LOCKED_SUFFIX, scope, false, false, false);
		}
		boolean free = line.kind() == CashRegister.Kind.FREE;
		return new Row(id, line.displayName(), scope, editable, editable && free, editable && free);
	}

	/** Die Ersatzzeile einer gesperrten Kasse ohne lesbaren Stand; ihr Inhalt ist unbekannt, sie lässt sich nicht ändern. */
	private static Row replacementRow(Event event, String id, BiPredicate<String, String> presentationUnavailable) {
		CashRegister.Kind kind = CashRegister.kindOf(id);
		String name = kind == CashRegister.Kind.FREE ? LOCKED_FREE : CashRegisters.defaultName(event, id) + LOCKED_SUFFIX;
		String scope = switch (kind) {
		case SHARED -> ALL_PRESENTATIONS;
		case FREE -> NO_SCOPE;
		case PRESENTATION -> {
			Presentation presentation = event.findPresentation(id);
			yield presentation != null ? presentationName(presentation) : orphanScope(presentationUnavailable.test(event.getId(), id));
		}
		};
		return new Row(id, name, scope, false, false, false);
	}

	/**
	 * Der Text unter „gilt für": „alle Vorstellungen" bei der gemeinsamen Kasse und einer freien für alle; bei einer freien
	 * Kasse mit Auswahl die Namen der ausgewählten Vorstellungen in der Reihenfolge des Events, mit Komma (nicht mehr
	 * vorhandene fehlen; ohne eine: „–"); bei einer Vorstellungskasse der Name der Vorstellung; bei einer verwaisten
	 * „– (Vorstellung gelöscht)" oder „– (Vorstellung nicht verfügbar)".
	 *
	 * @param presentationUnavailable bei einer verwaisten Kasse: ob ihre Vorstellung nur im Modell fehlt
	 */
	static String scopeText(Event event, VisibleCashRegister register, boolean presentationUnavailable) {
		return switch (register.kind()) {
		case SHARED -> ALL_PRESENTATIONS;
		case FREE -> freeScope(event, register.register());
		case PRESENTATION -> register.orphan() ? orphanScope(presentationUnavailable) : presentationName(register.presentation());
		};
	}

	/** „gilt für" einer freien Kasse (siehe {@link #scopeText}). */
	static String freeScope(Event event, CashRegister register) {
		if (register.isAllPresentations()) {
			return ALL_PRESENTATIONS;
		}
		String names = event.getPresentations().stream().filter(p -> register.getPresentationIds().contains(p.getId()))
				.map(CashRegisterSection::presentationName).collect(Collectors.joining(", "));
		return names.isEmpty() ? NO_SCOPE : names;
	}

	private static String orphanScope(boolean presentationUnavailable) {
		return presentationUnavailable ? PRESENTATION_UNAVAILABLE : PRESENTATION_DELETED;
	}

	private static String presentationName(Presentation presentation) {
		return EntityLabels.nameOrPlaceholder(presentation.getName());
	}

	private static boolean isBlank(String text) {
		return text == null || text.isBlank();
	}

	/** Ob der Schalter „eine gemeinsame Kasse" gewählt steht: die Kasse im Modell oder gesperrt. */
	static boolean sharedSelected(Event event, Map<String, LockedCashRegister> locked) {
		return CashRegisters.sharedSelected(event, locked.containsKey(CashRegister.SHARED_ID));
	}

	/** Ob der Schalter bedienbar ist: im Bearbeiten-Modus, nicht „noch nicht abgeglichen", gemeinsame Kasse nicht gesperrt. */
	static boolean switchEnabled(Map<String, LockedCashRegister> locked, boolean pending, boolean editMode) {
		return editMode && !pending && !locked.containsKey(CashRegister.SHARED_ID);
	}

	/**
	 * „gilt für" einer freien Kasse: „alle Vorstellungen" an oder aus. Die Auswahl bleibt dabei stehen; ausgewählte
	 * Vorstellungen, die es nicht mehr gibt, fallen weg.
	 */
	static void selectAllPresentations(Event event, String id, boolean all) {
		CashRegister register = freeRegister(event, id);
		if (register != null) {
			register.setAllPresentations(all);
			register.setPresentationIds(selectedIds(event, register, null, false));
		}
	}

	/**
	 * „gilt für" einer freien Kasse: die Vorstellung aus- oder abwählen; danach gilt die Kasse für die Auswahl, nicht mehr
	 * für alle. Ausgewählte Vorstellungen, die es nicht mehr gibt, fallen weg; die Auswahl steht in der Reihenfolge des
	 * Events.
	 */
	static void selectPresentation(Event event, String id, String presentationId, boolean selected) {
		CashRegister register = freeRegister(event, id);
		if (register != null) {
			register.setAllPresentations(false);
			register.setPresentationIds(selectedIds(event, register, presentationId, selected));
		}
	}

	private static CashRegister freeRegister(Event event, String id) {
		CashRegister register = event.findCashRegister(id);
		return register != null && register.kind() == CashRegister.Kind.FREE ? register : null;
	}

	/** Die Auswahl der Kasse ohne nicht mehr vorhandene Vorstellungen, mit {@code changed} an oder aus. */
	private static List<String> selectedIds(Event event, CashRegister register, String changed, boolean selected) {
		List<String> ids = new ArrayList<>();
		for (Presentation presentation : event.getPresentations()) {
			boolean chosen = presentation.getId().equals(changed) ? selected : register.getPresentationIds().contains(presentation.getId());
			if (chosen) {
				ids.add(presentation.getId());
			}
		}
		return ids;
	}

	/**
	 * Ändert den Namen der Kasse ({@link CashRegisters#setName}; leer: Vorgabename). Steht schon dieser Name da, ändert sich
	 * nichts: Der Vorgabename wird so nicht zum eigenen Namen, und eine gedachte Vorstellungskasse entsteht nicht.
	 *
	 * @return ob sich das Modell geändert hat
	 */
	static boolean rename(Event event, String id, String name) {
		String wanted = name == null ? "" : name.trim();
		CashRegister register = event.findCashRegister(id);
		Presentation presentation = event.findPresentation(id);
		if (register == null && presentation == null) {
			return false;
		}
		// Eine freie Kasse hat keinen Vorgabenamen: Sie wird mit ihrem gespeicherten Namen verglichen, sonst bliebe ein
		// gelöschter Name bei der Eingabe des Platzhalters ohne Namen.
		boolean free = register != null && register.kind() == CashRegister.Kind.FREE;
		boolean unchanged = wanted.isEmpty() ? register == null || isBlank(register.getName())
				: wanted.equals(free ? register.getName() : displayName(event, id));
		if (unchanged) {
			return false;
		}
		CashRegisters.setName(event, id, wanted);
		return true;
	}

	/** Die Stelle der Zeile mit der Kennung (die Auswahl nach einem Neuaufbau); -1, wenn es keine gibt. */
	static int indexOf(List<Row> rows, String id) {
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).id().equals(id)) {
				return i;
			}
		}
		return -1;
	}

	/** Der Name der Kasse ({@link CashRegisters#displayName}); eine gedachte Kasse heißt wie ein Objekt ohne eigenen Namen. */
	static String displayName(Event event, String id) {
		return CashRegisters.displayName(event, id);
	}

	/**
	 * Das Objekt im Modell, das eine Änderung an der Zeile verändert (für fremde Stände „in Bearbeitung"): die Kasse, bei
	 * einer gedachten Vorstellungskasse die Vorstellung; {@code null}, wenn es keins gibt.
	 */
	static Object modelOf(Event event, String id) {
		if (event == null) {
			return null;
		}
		CashRegister register = event.findCashRegister(id);
		return register != null ? register : event.findPresentation(id);
	}

	/**
	 * Warum sich die Kasse jetzt nicht ändern lässt: „Kassen noch nicht abgeglichen", gesperrt (SYS-008, SYS-009) oder
	 * wartend; {@code null}, wenn nichts dagegen spricht.
	 *
	 * @param name   der Anzeigename der Kasse
	 * @param locked ihre Sperre ({@link StoreEventRepository#lockedCashRegisters}) oder {@code null}
	 */
	static String refusal(String name, LockedCashRegister locked, boolean pending) {
		if (pending) {
			return PENDING;
		}
		if (locked == null) {
			return null;
		}
		return locked.message(EntityLabels.cashRegister(name));
	}

	/** {@link #DELETE_REFUSED} mit dem Namen der Kasse. */
	static String deleteRefused(String name) {
		return MessageTemplate.format(DELETE_REFUSED, name);
	}

	/** {@link #DELETE_CONFIRM} mit dem Namen der Kasse. */
	static String deleteConfirm(String name) {
		return MessageTemplate.format(DELETE_CONFIRM, name);
	}
}
