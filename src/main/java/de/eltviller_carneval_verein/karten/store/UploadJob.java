package de.eltviller_carneval_verein.karten.store;

import java.util.ArrayList;
import java.util.List;

/**
 * Ein Stapel zum Hochladen: die Versionen eines Speichervorgangs (eine {@code saveId}) in der Reihenfolge ihres
 * Entstehens. Der Auftrag merkt sich, bis wohin {@link Uploader#run} gekommen ist ({@code VERSIONS → FEED → CLAIMS →
 * DONE}, bei {@code CLAIMS} auch die nächste zu beanspruchende Version), und die Niederlagen, die dabei auftraten.
 * Scheitert ein Lauf, setzt der nächste genau dort fort. Nicht threadsicher; ein Auftrag gehört einem Thread.
 */
public final class UploadJob {

	/** Eine Version des Stapels mit den Bytes ihrer Versionsdatei. */
	public record Item(UnitKey unit, Version version, byte[] bytes) {
	}

	/** Die Stufe des Hochladens. */
	enum Stage {
		VERSIONS, FEED, CLAIMS, DONE
	}

	private final String saveId;
	private final List<Item> items;
	private Stage stage = Stage.VERSIONS;
	private int nextClaim;
	private final List<LostVersion> losses = new ArrayList<>();
	private boolean reported;

	/**
	 * @param saveId die ID des Speichervorgangs
	 * @param items  die Versionen in der Reihenfolge ihres Entstehens
	 */
	public UploadJob(String saveId, List<Item> items) {
		this.saveId = saveId;
		this.items = List.copyOf(items);
	}

	public String saveId() {
		return saveId;
	}

	public List<Item> items() {
		return items;
	}

	/** Ob alle Stufen durchlaufen sind. */
	public boolean isDone() {
		return stage == Stage.DONE;
	}

	Stage stage() {
		return stage;
	}

	/** Index der nächsten Version, deren Anspruch aussteht (nur in der Stufe {@code CLAIMS}). */
	int nextClaim() {
		return nextClaim;
	}

	void enter(Stage next) {
		stage = next;
		nextClaim = 0;
	}

	void claimed(int count) {
		nextClaim = count;
	}

	/**
	 * Die Niederlage, in deren verlorenen Versionen die Version als Vorgänger vorkommt (gleiche Einheit), oder -1.
	 */
	int lossContaining(UnitKey unit, VersionId version) {
		for (int i = 0; i < losses.size(); i++) {
			LostVersion loss = losses.get(i);
			if (loss.unit().equals(unit) && loss.mine().contains(version)) {
				return i;
			}
		}
		return -1;
	}

	void addLoss(LostVersion loss) {
		losses.add(loss);
	}

	/** Hängt die Version an die verlorenen Versionen der Niederlage an: Sie baut auf einer verlorenen auf. */
	void extendLoss(int index, VersionId version) {
		LostVersion loss = losses.get(index);
		List<VersionId> mine = new ArrayList<>(loss.mine());
		mine.add(version);
		losses.set(index, new LostVersion(loss.unit(), List.copyOf(mine), loss.winner()));
	}

	/**
	 * Die bisher gefundenen Niederlagen, ohne sie als gemeldet zu markieren: für einen Lauf, der nach einer Störung
	 * abbricht (der Empfänger muss doppelte Meldungen selbst aussortieren).
	 */
	List<LostVersion> losses() {
		return List.copyOf(losses);
	}

	/** Die Niederlagen des Auftrags, einmal; danach leer. */
	List<LostVersion> takeLosses() {
		if (reported) {
			return List.of();
		}
		reported = true;
		return List.copyOf(losses);
	}
}
