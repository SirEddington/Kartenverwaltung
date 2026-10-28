package de.eltviller_carneval_verein.karten.repository;

import java.util.function.Consumer;

/**
 * Wird von einem Repository ({@link StoreEventRepository}, {@link StoreHallRepository}) nach jedem Speichern oder
 * Löschen aufgerufen, egal von welcher Stelle aus gespeichert wurde. So kann der Änderungsmelder den gespeicherten
 * Stand nachführen, ohne dass jeder Speicherweg daran denken muss. Ebenso erfahren Änderungsmelder und Ansichten, wenn
 * Stände anderer Rechner eingearbeitet wurden ({@code …ByOthers}).
 *
 * @param <T> Event oder Hall
 */
public interface RepositoryListener<T> {

	/** Die Entität wurde erfolgreich in die Datei geschrieben. */
	default void saved(T entity) {
	}

	/** Die Entität wurde gelöscht. */
	default void deleted(T entity) {
	}

	/**
	 * Gleich werden Stände eines anderen Rechners in die Entität eingearbeitet ({@link #changedByOthers} folgt). Eine
	 * noch nicht verglichene Änderung des Nutzers lässt sich jetzt noch erkennen.
	 */
	default void changingByOthers(T entity) {
	}

	/**
	 * Stände eines anderen Rechners wurden in die Entität eingearbeitet; Vergleichsstand und Cache sind nachgeführt.
	 * {@code batch} nennt die Einheiten und spielt sie auf einer Kopie nach.
	 */
	default void changedByOthers(T entity, ForeignBatch<T> batch) {
	}

	/** Ein anderer Rechner hat die Entität angelegt oder wiederbelebt; sie steht jetzt im Cache. */
	default void addedByOthers(T entity) {
	}

	/** Ein anderer Rechner hat die Entität gelöscht (oder sie ist unlesbar geworden); sie hat den Cache verlassen. */
	default void removedByOthers(T entity) {
	}

	/** Ein Listener, der bei jedem fremden Rückruf ({@code changedByOthers}, {@code addedByOthers}, {@code removedByOthers}) {@code action} mit der Entität aufruft. */
	static <T> RepositoryListener<T> onForeignChange(Consumer<? super T> action) {
		return new RepositoryListener<>() {
			@Override
			public void changedByOthers(T entity, ForeignBatch<T> batch) {
				action.accept(entity);
			}

			@Override
			public void addedByOthers(T entity) {
				action.accept(entity);
			}

			@Override
			public void removedByOthers(T entity) {
				action.accept(entity);
			}
		};
	}

	/** Wie {@link #onForeignChange(Consumer)}, für eine Aktion, die die Entität nicht braucht. */
	static <T> RepositoryListener<T> onForeignChange(Runnable action) {
		return onForeignChange(entity -> action.run());
	}
}
