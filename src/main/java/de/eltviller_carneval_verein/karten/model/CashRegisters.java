package de.eltviller_carneval_verein.karten.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Die fachlichen Regeln für Kassen am Event (KARV-144): wirksamer Stand, "zum Ändern holen", eigene Daten, sichtbare
 * Kassen, Vorgabenamen, Entfallen einer Vorstellung und zweiter Ist-Betrag. Statische Methoden ohne JavaFX, wie
 * {@link SaleDates}. Die Regeln stehen im Spec "Kassen am Event", Abschnitt "Modell".
 */
public final class CashRegisters {

	/**
	 * Der Platzhalter für einen fehlenden Namen in Anzeigen; {@code validation.EntityLabels.NAME_PLACEHOLDER} verweist
	 * hierher ({@code model} darf nicht von {@code validation} abhängen).
	 */
	public static final String NAME_PLACEHOLDER = "(ohne Name)";
	private static final String NEW_FREE_NAME = "Neue Kasse";
	private static final String SHARED_NAME = "Gemeinsame Kasse";
	private static final String ORPHAN_NAME = "Kasse ohne Vorstellung";
	private static final String PRESENTATION_NAME_PREFIX = "Kasse ";

	private CashRegisters() {
	}

	/** Ist-Betrag in Cent und Kommentar einer Kasse. */
	public record CashState(int actualCashAmount, String comment) {

		/** Ist-Betrag 0 und Kommentar leer oder {@code null}. */
		public boolean isEmpty() {
			return actualCashAmount == 0 && isBlank(comment);
		}
	}

	/**
	 * Eine Zeile der sichtbaren Kassen. {@code register} ist {@code null} bei einer nur gedachten Vorstellungskasse,
	 * {@code presentation} ist {@code null} bei verwaisten, gemeinsamen und freien Kassen.
	 */
	public record VisibleCashRegister(String id, CashRegister.Kind kind, CashRegister register, Presentation presentation,
			String displayName, boolean orphan, CashState cash) {
	}

	private static boolean isBlank(String text) {
		return text == null || text.isBlank();
	}

	private static CashState stateOf(CashRegister register) {
		return new CashState(register.getActualCashAmount(), register.getCashDifferenceComment());
	}

	/** Hat die Kasse einen Ist-Betrag ungleich 0 oder einen Kommentar? */
	public static boolean hasCash(CashRegister register) {
		return !stateOf(register).isEmpty();
	}

	private static CashState oldFieldsOf(Presentation presentation) {
		return new CashState(presentation.getActualCashAmount(), presentation.getCashDifferenceComment());
	}

	private static void clearOldFields(Presentation presentation) {
		presentation.setActualCashAmount(0);
		presentation.setCashDifferenceComment(null);
	}

	/** Der wirksame Stand der Kasse einer Vorstellung: das Kassen-Objekt, sonst die alten Felder der Vorstellung. */
	public static CashState effective(Event event, Presentation presentation) {
		CashRegister register = event.findCashRegister(presentation.getId());
		return register != null ? stateOf(register) : oldFieldsOf(presentation);
	}

	/**
	 * Holt die Kasse der Vorstellung zum Ändern. Gibt es sie nicht als Objekt, wird sie angelegt und bekommt Ist-Betrag
	 * und Kommentar aus den alten Feldern; diese werden dann auf 0 und {@code null} gesetzt. Gab es das Objekt schon,
	 * bleiben die alten Felder unberührt.
	 */
	public static CashRegister forEdit(Event event, Presentation presentation) {
		CashRegister register = event.findCashRegister(presentation.getId());
		if (register == null) {
			register = new CashRegister(presentation.getId());
			register.setActualCashAmount(presentation.getActualCashAmount());
			register.setCashDifferenceComment(presentation.getCashDifferenceComment());
			clearOldFields(presentation);
			event.putCashRegister(register);
		}
		return register;
	}

	/** Setzt den Ist-Betrag der Kasse der Vorstellung; leert die alten Felder (auch den zweiten Ist-Betrag). */
	public static void setActualCash(Event event, Presentation presentation, int cents) {
		forEdit(event, presentation).setActualCashAmount(cents);
		clearOldFields(presentation);
	}

	/** Setzt den Kommentar der Kasse der Vorstellung; leert die alten Felder (auch den zweiten Ist-Betrag). */
	public static void setComment(Event event, Presentation presentation, String comment) {
		forEdit(event, presentation).setCashDifferenceComment(isBlank(comment) ? null : comment);
		clearOldFields(presentation);
	}

	/**
	 * Setzt den eigenen Namen der Kasse; ein leerer Name wird {@code null} (Vorgabename). Eine Vorstellungskasse wird
	 * dafür über {@link #forEdit} geholt, wenn es ihre Vorstellung gibt. Eine unbekannte Kennung bleibt ohne Wirkung.
	 */
	public static void setName(Event event, String id, String name) {
		String value = isBlank(name) ? null : name;
		CashRegister register = event.findCashRegister(id);
		Presentation presentation = event.findPresentation(id);
		if (presentation != null && register == null) {
			register = forEdit(event, presentation);
		}
		if (register != null) {
			register.setName(value);
		}
	}

	/**
	 * Der zweite Ist-Betrag: Gibt es die Kasse der Vorstellung als Objekt und steht in den alten Feldern ein Betrag
	 * ungleich 0 oder ein Kommentar, ist das der Stand der alten Felder; sonst leer.
	 */
	public static Optional<CashState> secondAmount(Event event, Presentation presentation) {
		if (event.findCashRegister(presentation.getId()) == null) {
			return Optional.empty();
		}
		CashState old = oldFieldsOf(presentation);
		return old.isEmpty() ? Optional.empty() : Optional.of(old);
	}

	/** Verwirft den zweiten Ist-Betrag: leert die alten Felder der Vorstellung. */
	public static void discardSecondAmount(Presentation presentation) {
		clearOldFields(presentation);
	}

	/** Übernimmt die alten Felder (Ist-Betrag und Kommentar) einer Vorstellung in eine andere. */
	public static void copyOldFields(Presentation from, Presentation to) {
		to.setActualCashAmount(from.getActualCashAmount());
		to.setCashDifferenceComment(from.getCashDifferenceComment());
	}

	/**
	 * Hat die Kasse eigene Daten? Eine Vorstellungskasse, wenn ihr Ist-Betrag nicht 0 ist, ihr Kommentar nicht leer ist
	 * oder ihr Name gesetzt ist und das Event ihre Vorstellung enthält. Gemeinsame und freie Kassen immer.
	 */
	public static boolean hasOwnData(Event event, CashRegister register) {
		if (register.kind() != CashRegister.Kind.PRESENTATION) {
			return true;
		}
		return hasCash(register) || (!isBlank(register.getName()) && event.findPresentation(register.getId()) != null);
	}

	/** Ist die gemeinsame Kasse gewählt, enthält die Liste also die Kasse {@code _event}? */
	public static boolean sharedSelected(Event event) {
		return event.findCashRegister(CashRegister.SHARED_ID) != null;
	}

	/**
	 * Gilt die gemeinsame Kasse als gewählt: sie steht im Modell, oder sie ist gesperrt (auch wenn sie im Modell fehlt).
	 *
	 * @param sharedLocked ob die Einheit der gemeinsamen Kasse gesperrt ist oder wartet
	 */
	public static boolean sharedSelected(Event event, boolean sharedLocked) {
		return sharedSelected(event) || sharedLocked;
	}

	/**
	 * Wählt die gemeinsame Kasse oder schaltet sie ab. Abschalten wird abgelehnt ({@code false}), solange die Kasse einen
	 * Ist-Betrag ungleich 0 oder einen Kommentar hat.
	 */
	public static boolean selectShared(Event event, boolean shared) {
		CashRegister register = event.findCashRegister(CashRegister.SHARED_ID);
		if (shared) {
			if (register == null) {
				event.putCashRegister(new CashRegister(CashRegister.SHARED_ID));
			}
			return true;
		}
		if (register == null) {
			return true;
		}
		if (hasCash(register)) {
			return false;
		}
		event.removeCashRegister(CashRegister.SHARED_ID);
		return true;
	}

	/** Legt eine freie Kasse für alle Vorstellungen an, "Neue Kasse", bei Gleichheit "Neue Kasse 2", "Neue Kasse 3" ... */
	public static CashRegister addFree(Event event) {
		CashRegister register = new CashRegister(CashRegister.newFreeId());
		register.setName(freeName(event));
		register.setAllPresentations(true);
		event.putCashRegister(register);
		return register;
	}

	private static String freeName(Event event) {
		String name = NEW_FREE_NAME;
		for (int i = 2; nameTaken(event, name); i++) {
			name = NEW_FREE_NAME + " " + i;
		}
		return name;
	}

	private static boolean nameTaken(Event event, String name) {
		String wanted = name.trim().toLowerCase(Locale.GERMAN);
		return event.getCashRegisters().stream().map(CashRegister::getName).anyMatch(n -> n != null && n.trim().toLowerCase(Locale.GERMAN).equals(wanted));
	}

	/** Entfernt eine freie Kasse; {@code false}, wenn sie Ist-Betrag ungleich 0 oder Kommentar hat oder es sie nicht gibt. */
	public static boolean removeFree(Event event, String id) {
		CashRegister register = event.findCashRegister(id);
		if (register == null || register.kind() != CashRegister.Kind.FREE || hasCash(register)) {
			return false;
		}
		return event.removeCashRegister(id);
	}

	/** Der Name der Kasse: ihr eigener, sonst der Vorgabename. */
	public static String displayName(Event event, CashRegister register) {
		return isBlank(register.getName()) ? defaultName(event, register.getId()) : register.getName();
	}

	/** Der Name der Kasse mit der Kennung ({@link #displayName(Event, CashRegister)}); ohne Objekt ihr Vorgabename. */
	public static String displayName(Event event, String id) {
		CashRegister register = event.findCashRegister(id);
		return register != null ? displayName(event, register) : defaultName(event, id);
	}

	/**
	 * Der Vorgabename der Kasse mit der Kennung: "Gemeinsame Kasse", bei einer Vorstellungskasse "Kasse " und der Name der
	 * Vorstellung, ohne Vorstellung im Event "Kasse ohne Vorstellung"; eine freie Kasse hat keinen und heißt
	 * {@value #NAME_PLACEHOLDER}.
	 */
	public static String defaultName(Event event, String id) {
		return switch (CashRegister.kindOf(id)) {
		case SHARED -> SHARED_NAME;
		case FREE -> NAME_PLACEHOLDER;
		case PRESENTATION -> presentationDefaultName(event.findPresentation(id));
		};
	}

	private static String presentationDefaultName(Presentation presentation) {
		if (presentation == null) {
			return ORPHAN_NAME;
		}
		return PRESENTATION_NAME_PREFIX + (isBlank(presentation.getName()) ? NAME_PLACEHOLDER : presentation.getName());
	}

	/**
	 * Die sichtbaren Kassen in der Reihenfolge: gemeinsame Kasse, je Vorstellung ihre Kasse (wenn keine gemeinsame
	 * gewählt ist oder der wirksame Stand Betrag, Kommentar oder Namen hat), freie Kassen nach Name, verwaiste
	 * Vorstellungskassen mit Betrag oder Kommentar nach Name.
	 */
	public static List<VisibleCashRegister> visible(Event event) {
		List<VisibleCashRegister> result = new ArrayList<>();
		CashRegister sharedRegister = event.findCashRegister(CashRegister.SHARED_ID);
		boolean shared = sharedRegister != null;
		if (shared) {
			result.add(line(event, sharedRegister, false));
		}
		for (Presentation presentation : event.getPresentations()) {
			CashRegister register = event.findCashRegister(presentation.getId());
			boolean named = register != null && !isBlank(register.getName());
			CashState cash = effective(event, presentation);
			if (!shared || !cash.isEmpty() || named) {
				result.add(new VisibleCashRegister(presentation.getId(), CashRegister.Kind.PRESENTATION, register, presentation,
						displayName(event, presentation.getId()), false, cash));
			}
		}
		result.addAll(sortedByName(event.getCashRegisters().stream().filter(r -> r.kind() == CashRegister.Kind.FREE)
				.map(r -> line(event, r, false)).toList()));
		result.addAll(sortedByName(
				event.getCashRegisters().stream()
						.filter(r -> r.kind() == CashRegister.Kind.PRESENTATION && event.findPresentation(r.getId()) == null
								&& hasCash(r))
						.map(r -> line(event, r, true)).toList()));
		return result;
	}

	/** Eine Zeile für eine Kasse ohne Vorstellung in der Zeile (gemeinsame, freie oder verwaiste Kasse). */
	private static VisibleCashRegister line(Event event, CashRegister register, boolean orphan) {
		return new VisibleCashRegister(register.getId(), register.kind(), register, null, displayName(event, register), orphan,
				stateOf(register));
	}

	private static List<VisibleCashRegister> sortedByName(List<VisibleCashRegister> lines) {
		return lines.stream().sorted(Comparator.comparing((VisibleCashRegister l) -> l.displayName().toLowerCase(Locale.GERMAN))
				.thenComparing(VisibleCashRegister::id)).toList();
	}

	/**
	 * Eine Vorstellung entfällt: Hat der wirksame Stand ihrer Kasse Betrag oder Kommentar, wird die Kasse zum Ändern
	 * geholt und bekommt ohne eigenen Namen ihren Vorgabenamen als Namen; sie bleibt als verwaiste Kasse sichtbar. Sonst
	 * wird ein vorhandenes Kassen-Objekt aus der Liste genommen. Aufzurufen, bevor die Vorstellung aus dem Event genommen wird.
	 */
	public static void presentationRemoved(Event event, Presentation presentation) {
		if (!effective(event, presentation).isEmpty()) {
			CashRegister register = forEdit(event, presentation);
			if (isBlank(register.getName())) {
				register.setName(presentationDefaultName(presentation));
			}
		} else {
			event.removeCashRegister(presentation.getId());
		}
	}
}
