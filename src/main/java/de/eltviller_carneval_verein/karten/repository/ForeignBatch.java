package de.eltviller_carneval_verein.karten.repository;

import java.util.Set;
import java.util.function.Consumer;

import de.eltviller_carneval_verein.karten.store.UnitKey;

/**
 * Die fremden Stände, die in einem Schritt in eine Entität eingearbeitet wurden. Der Änderungsmelder spielt sie auf
 * einer Kopie seines gespeicherten Stands nach, damit dieser den fremden Stand enthält und "Nicht speichern" ihn nicht
 * wieder entfernt.
 *
 * @param <T> Event oder Hall
 */
public final class ForeignBatch<T> {

	private final Set<UnitKey> units;
	private final Consumer<T> replay;

	/**
	 * @param units  die eingearbeiteten Einheiten
	 * @param replay arbeitet dieselben Stände in eine Kopie ein
	 */
	ForeignBatch(Set<UnitKey> units, Consumer<T> replay) {
		this.units = Set.copyOf(units);
		this.replay = replay;
	}

	/** Die eingearbeiteten Einheiten. */
	public Set<UnitKey> units() {
		return units;
	}

	/**
	 * Arbeitet dieselben Stände in {@code copy} ein, wie sie in die Entität im Cache eingearbeitet wurden. Cache,
	 * Vergleichsstand und Listener des Repositorys bleiben unberührt.
	 */
	public void replayOn(T copy) {
		replay.accept(copy);
	}
}
