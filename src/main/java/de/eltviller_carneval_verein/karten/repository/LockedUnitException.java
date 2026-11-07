package de.eltviller_carneval_verein.karten.repository;

import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/**
 * Eine Einheit des Versionsspeichers ist gesperrt (beschädigt oder mit einer neueren Programmversion gespeichert) und
 * lässt sich nicht ändern. Die Meldung ist für den Nutzer bestimmt: Sie nennt die Einheit mit ihrer lesbaren Bezeichnung
 * ({@link #useLabels}), ohne eine mit ihrem Pfad.
 */
public class LockedUnitException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private static final Logger LOG = Logger.getLogger(LockedUnitException.class.getName());

	private static final Function<UnitKey, String> NO_LABELS = unit -> null;

	/** Die Bezeichnungen der Einheiten für SYS-008 und SYS-009 (in der App {@code ConflictLabels}). */
	private static volatile Function<UnitKey, String> labels = NO_LABELS;

	public LockedUnitException(String message) {
		super(message);
	}

	/**
	 * Setzt die Bezeichnungen, mit denen SYS-008 und SYS-009 die Einheit nennen (Spec "Meldungen": lesbare Bezeichnung
	 * statt Pfad); liefern sie {@code null} oder scheitern sie, steht der Pfad da. {@code null} nimmt sie wieder weg.
	 */
	public static void useLabels(Function<UnitKey, String> unitLabels) {
		labels = unitLabels != null ? unitLabels : NO_LABELS;
	}

	/** Der Text (SYS-008 bzw. SYS-009) mit der Bezeichnung der Einheit, wenn sie nach {@code status} gesperrt ist; sonst {@code null}. */
	static String lockMessage(UnitKey unit, Status status) {
		return status == Status.LOCKED_DAMAGED || status == Status.LOCKED_FORMAT ? StoreMessages.locked(label(unit), status)
				: null;
	}

	/**
	 * SYS-009 mit der Bezeichnung der Einheit: Ihr Inhalt stammt von einem neueren Programm (KARV-135), etwa ein gesperrter
	 * Vorfahr einer geänderten Einheit oder ein eigener Inhalt, den die Inhaltsprüfung für zu neu hält.
	 */
	static LockedUnitException newerFormat(UnitKey unit) {
		return new LockedUnitException(StoreMessages.newerFormat(label(unit)));
	}

	private static String label(UnitKey unit) {
		try {
			String label = labels.apply(unit);
			if (label != null) {
				return label;
			}
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "Keine Bezeichnung für " + unit.relativePath(), e);
		}
		return unit.relativePath();
	}

	/** Ein anderer Rechner hat das Event bzw. die Halle inzwischen ersetzt oder gelöscht: Das alte Objekt zu speichern überschriebe seinen Stand. */
	static LockedUnitException replacedMeanwhile(String name) {
		return new LockedUnitException("„" + EntityLabels.nameOrPlaceholder(name)
				+ "“ wurde inzwischen auf einem anderen Rechner geändert. Bitte die Ansicht neu öffnen.");
	}
}
