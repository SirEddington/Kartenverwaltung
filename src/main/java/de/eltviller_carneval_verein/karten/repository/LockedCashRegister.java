package de.eltviller_carneval_verein.karten.repository;

import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.validation.MessageTemplate;

/**
 * Eine Kassen-Einheit, die sich nicht ändern lässt (KARV-144): gesperrt (SYS-008, SYS-009) oder wartend auf eine Datei
 * aus dem gemeinsamen Ordner, am Stand des Versionsspeichers ({@link StoreEventRepository#lockedCashRegisters}). Die
 * Event-Verwaltung behandelt eine wartende Kasse wie eine gesperrte.
 *
 * @param id       die Kennung der Kasse ({@code UnitKey#cashRegisterId})
 * @param status   die Sperre ({@code LOCKED_DAMAGED}, {@code LOCKED_FORMAT}) oder {@code WAITING}
 * @param readable ob das Zusammensetzen einen Stand der Kasse liest (sie dann im Modell steht); ohne lesbaren Stand
 *                 ersetzt eine Zeile „(gesperrt)" die Kasse
 */
public record LockedCashRegister(String id, UnitState.Status status, boolean readable) {

	private static final String WAITING_TEXT = "&1 wartet noch auf eine Datei aus dem gemeinsamen Ordner. Ändern ist erst "
			+ "möglich, wenn sie angekommen ist.";

	/**
	 * Warum sich die Kasse nicht ändern lässt: SYS-008 bzw. SYS-009 ({@link StoreMessages#locked}) oder, wenn sie wartet,
	 * dass sie noch auf eine Datei wartet. Event-Verwaltung und Kassenabgleich nennen die Sperre mit demselben Text.
	 *
	 * @param label wie die Meldung die Kasse nennt ({@code EntityLabels.cashRegister})
	 */
	public String message(String label) {
		return status == UnitState.Status.WAITING ? MessageTemplate.format(WAITING_TEXT, label) : StoreMessages.locked(label, status);
	}

	/**
	 * Warum sich die Vorstellung wegen ihrer Kasse nicht löschen lässt: {@link StoreMessages#lockedBelow} oder, wenn die
	 * Kasse wartet, dass sie noch auf eine Datei wartet ({@link StoreEventRepository#requireCashRegisterWritable}).
	 *
	 * @param name wie die Meldung die Vorstellung nennt
	 */
	public String deletionMessage(String name) {
		return status == UnitState.Status.WAITING
				? "\"" + name + "\": Die Kasse wartet noch auf eine Datei aus dem gemeinsamen Ordner. Löschen ist erst "
						+ "möglich, wenn sie angekommen ist."
				: StoreMessages.lockedBelow(name, status);
	}
}
