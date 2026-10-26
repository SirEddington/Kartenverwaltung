package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lädt einen Stapel in den gemeinsamen Ordner, in dieser Reihenfolge: erst alle Versionsdateien, dann eine neue
 * Änderungsliste mit allen Versionen des Stapels, dann je Version der Anspruch. Die Reihenfolge ist wichtig: Ein
 * Anspruch ohne Versionsdatei oder Liste wäre für andere nicht auffindbar. Nach einem Fehler setzt ein erneuter Aufruf
 * mit demselben Auftrag bei der Stufe fort, die scheiterte.
 */
public final class Uploader {

	private final VersionStore shared;
	private final FeedWriter feed;
	private final Arbiter arbiter;

	/**
	 * @param shared  der gemeinsame Bestand
	 * @param feed    der Schreiber der Änderungslisten dieses Rechners
	 * @param arbiter der Schiedsrichter über die Ansprüche
	 */
	public Uploader(VersionStore shared, FeedWriter feed, Arbiter arbiter) {
		this.shared = shared;
		this.feed = feed;
		this.arbiter = arbiter;
	}

	/**
	 * Führt den Auftrag ab der gemerkten Stufe zu Ende.
	 *
	 * @return die Niederlagen des Auftrags; eine Version, die auf einer verlorenen derselben Einheit aufbaut, bekommt
	 *         keinen Anspruch und steht mit ihr in derselben Niederlage. Niederlagen eines gescheiterten Laufs werden mit
	 *         dem Lauf gemeldet, der den Auftrag beendet. Für einen fertigen Auftrag ist die Liste leer.
	 * @throws ContentMismatchException wenn im gemeinsamen Ordner eine Versionsdatei mit anderem Inhalt liegt
	 * @throws DuplicateMachineException wenn die Nummer der Änderungsliste belegt ist
	 * @throws IOException               bei jeder anderen Störung
	 */
	public List<LostVersion> run(UploadJob job) throws IOException {
		if (job.stage() == UploadJob.Stage.VERSIONS) {
			for (UploadJob.Item item : job.items()) {
				uploadVersion(item);
			}
			job.enter(UploadJob.Stage.FEED);
		}
		if (job.stage() == UploadJob.Stage.FEED) {
			List<FeedEntry> entries = new ArrayList<>();
			for (UploadJob.Item item : job.items()) {
				entries.add(new FeedEntry(item.unit().relativePath(), item.version().id(), item.version().claimName()));
			}
			feed.append(entries);
			job.enter(UploadJob.Stage.CLAIMS);
		}
		if (job.stage() == UploadJob.Stage.CLAIMS) {
			for (int i = job.nextClaim(); i < job.items().size(); i++) {
				claim(job, job.items().get(i));
				job.claimed(i + 1);
			}
			job.enter(UploadJob.Stage.DONE);
		}
		return job.takeLosses();
	}

	private void uploadVersion(UploadJob.Item item) throws IOException {
		UnitKey unit = item.unit();
		VersionId id = item.version().id();
		if (shared.writeVersion(unit, id, item.bytes())) {
			return;
		}
		byte[] existing = shared.readVersionBytes(unit, id).orElseThrow(
				() -> new IOException("Die Versionsdatei " + unit.relativePath() + "/" + id + ".json fehlt trotz vorhandenem Namen."));
		if (!Arrays.equals(existing, item.bytes())) {
			throw new ContentMismatchException(unit.relativePath() + "/" + id + ".json");
		}
	}

	private void claim(UploadJob job, UploadJob.Item item) throws IOException {
		Version version = item.version();
		VersionId parent = version.firstParent();
		int parentLoss = parent == null ? -1 : job.lossContaining(item.unit(), parent);
		if (parentLoss >= 0) {
			job.extendLoss(parentLoss, version.id());
			return;
		}
		ClaimOutcome outcome = arbiter.claim(item.unit(), version, item.bytes());
		if (!outcome.won()) {
			job.addLoss(new LostVersion(item.unit(), List.of(version.id()), outcome.claim().version()));
		}
	}
}
