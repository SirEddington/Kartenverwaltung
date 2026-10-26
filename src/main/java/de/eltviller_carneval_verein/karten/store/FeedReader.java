package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Liest die Änderungslisten der anderen Rechner aus dem gemeinsamen Ordner und holt die genannten Versions- und
 * Anspruchsdateien in den lokalen Bestand. So erfährt ein Rechner binnen weniger Sekunden, was ein anderer verkauft hat.
 * <p>
 * Je fremdem Rechner wird nur geprüft, ob {@code feeds/<ID>/<Position+1>.json} existiert. Eine Liste wird in dieser
 * Reihenfolge verarbeitet: erst alle Versionsdateien, dann die Anspruchsdateien. Fehlt eine Versionsdatei noch (bei
 * Nextcloud kann die Liste vor den Dateien ankommen), bleibt die Position stehen; nach fünf Minuten wird ein Vollabgleich
 * angefordert und die Liste übersprungen. Fehlt eine Anspruchsdatei noch, kommt die Version auf eine Beobachtungsliste,
 * die bei jedem Durchlauf neu geprüft wird, bis der Anspruch erscheint oder fünf Minuten vergangen sind.
 * <p>
 * Ein vorhandener lokaler Anspruch wird nie ersetzt. Weicht er ab (der gemeinsame nennt eine andere Version, oder er ist
 * der eigene und der fremde Eintrag nennt eine andere Version), meldet der Durchlauf den Platz als umstritten; passt eine
 * kopierte Versionsdatei nicht zum Prüfwert des gemeinsamen Anspruchs, die Einheit als beschädigt.
 * <p>
 * Ergebnisse, die ein gescheiterter Durchlauf schon gesammelt hat, gehen nicht verloren: Der nächste erfolgreiche
 * {@link #poll()} liefert sie mit. Die Klasse ist für den einen Hintergrund-Thread gedacht, der den gemeinsamen Ordner
 * bedient; die Methoden sind trotzdem synchronisiert.
 */
public final class FeedReader {

	/** So viele Listendateien liest ein Durchlauf höchstens, alle Rechner zusammen. */
	static final int MAX_FEEDS_PER_POLL = 200;

	/** So lange wartet eine Liste auf eine fehlende Versionsdatei, bevor sie übersprungen wird. */
	static final Duration WAIT_LIMIT = Duration.ofMinutes(5);

	/** So lange wird eine fehlende Anspruchsdatei beobachtet. */
	static final Duration WATCH_LIMIT = Duration.ofMinutes(5);

	private static final Pattern MACHINE_ID = Pattern.compile("[0-9a-z]{26}");
	private static final String FEEDS = "feeds";

	/** Eine beobachtete Anspruchsdatei. */
	private record Watch(UnitKey unit, VersionId version, String claimName) {
	}

	private final VersionStore shared;
	private final VersionStore local;
	private final String ownMachineId;
	private final SyncPositions positions;
	private final Clock clock;

	/** Beobachtete Ansprüche mit dem Zeitpunkt, seit dem sie fehlen. */
	private final Map<Watch, Instant> watched = new LinkedHashMap<>();
	/** Listen ({@code <Rechner>/<Nr>}), die auf eine Versionsdatei warten, mit dem Zeitpunkt der ersten Beobachtung. */
	private final Map<String, Instant> waiting = new HashMap<>();

	// Noch nicht gemeldete Ergebnisse; sie überstehen einen gescheiterten Durchlauf
	private final Set<UnitKey> changed = new LinkedHashSet<>();
	private final List<PendingSighting> pending = new ArrayList<>();
	private final Set<FeedPoll.Contested> contested = new LinkedHashSet<>();
	private final Set<UnitKey> damaged = new LinkedHashSet<>();
	private boolean fullSyncNeeded;
	private boolean positionsDirty;

	/**
	 * @param shared       der gemeinsame Bestand
	 * @param local        der lokale Bestand
	 * @param ownMachineId die Kennung dieses Rechners; seine eigene Liste wird nie gelesen
	 * @param positions    die zuletzt gelesenen Nummern
	 * @param clock        die Uhr für die Fristen
	 */
	public FeedReader(VersionStore shared, VersionStore local, String ownMachineId, SyncPositions positions,
			Clock clock) {
		this.shared = shared;
		this.local = local;
		this.ownMachineId = ownMachineId;
		this.positions = positions;
		this.clock = clock;
	}

	/**
	 * Ein Durchlauf: Beobachtungen nachprüfen, dann je fremdem Rechner (nach ID sortiert) die Listen ab Position+1
	 * lesen, höchstens {@value #MAX_FEEDS_PER_POLL} Dateien insgesamt. Haben sich Positionen geändert, werden sie
	 * gespeichert.
	 *
	 * @throws IOException bei einer Störung im gemeinsamen Ordner; Position und Beobachtungen bleiben dann in einem
	 *                     Zustand, von dem der nächste Durchlauf sauber weitermacht
	 */
	public synchronized FeedPoll poll() throws IOException {
		Instant now = clock.instant();
		recheckWatched(now);
		int budget = MAX_FEEDS_PER_POLL;
		for (String peer : peers()) {
			if (budget == 0) {
				break;
			}
			budget = readPeer(peer, now, budget);
		}
		if (positionsDirty) {
			positions.save();
			positionsDirty = false;
		}
		FeedPoll result = new FeedPoll(changed, pending, fullSyncNeeded, contested, damaged);
		changed.clear();
		pending.clear();
		contested.clear();
		damaged.clear();
		fullSyncNeeded = false;
		return result;
	}

	/**
	 * Listet {@code feeds/} auf; jeder Unterordner mit gültiger Rechner-ID, den es noch nicht gibt, wird ab Position 0
	 * gelesen. Die eigene ID zählt nicht.
	 */
	public synchronized void discoverMachines() throws IOException {
		Set<String> known = positions.machines();
		for (String id : peerDirectories()) {
			if (!known.contains(id)) {
				positions.set(id, 0);
				positionsDirty = true;
			}
		}
	}

	/**
	 * Setzt die Position jedes Rechners im gemeinsamen Ordner auf die höchste vorhandene Nummer und speichert sie.
	 * Wartende Listen und der Wunsch nach einem Vollabgleich entfallen; Beobachtungen und noch nicht gemeldete Ergebnisse
	 * bleiben.
	 * <p>
	 * Kein Aufrufer im Programm, nur für Tests: Der {@link SyncService} darf nicht springen. Jeder übersprungene Eintrag
	 * ist ein Eintrag, der einen abweichenden lokalen Anspruch nicht mehr als umstritten melden kann
	 * ({@link FeedPoll#contested()}); der Vollabgleich vergleicht nur eigene Ansprüche, und erst nach bis zu zehn
	 * Minuten. Erneutes Lesen schon verarbeiteter Listen kostet dagegen nur einen Vergleich je Eintrag.
	 */
	synchronized void jumpToLatest() throws IOException {
		jumpTo(latestNumbers());
	}

	/**
	 * Die höchste vorhandene Listennummer je fremdem Rechner im gemeinsamen Ordner (ohne den eigenen). Kein Aufrufer im
	 * Programm (siehe {@link #jumpToLatest()}).
	 */
	synchronized Map<String, Long> latestNumbers() throws IOException {
		Map<String, Long> latest = new TreeMap<>();
		for (String id : peerDirectories()) {
			latest.put(id, Feeds.highestFeedNumber(shared, id));
		}
		return latest;
	}

	/**
	 * Setzt die Positionen der genannten Rechner und speichert sie; andere Rechner behalten ihre Position. Wartende Listen
	 * und der Wunsch nach einem Vollabgleich entfallen, Beobachtungen und noch nicht gemeldete Ergebnisse bleiben. Kein
	 * Aufrufer im Programm (siehe {@link #jumpToLatest()}).
	 *
	 * @param numbers die Nummern, etwa aus {@link #latestNumbers()}
	 */
	synchronized void jumpTo(Map<String, Long> numbers) throws IOException {
		numbers.forEach(positions::set);
		positions.save();
		positionsDirty = false;
		waiting.clear();
		fullSyncNeeded = false;
	}

	/** Die Rechner, deren Listen gelesen werden: bekannte Positionen mit gültiger ID, ohne den eigenen, sortiert. */
	private Set<String> peers() {
		Set<String> peers = new TreeSet<>();
		for (String id : positions.machines()) {
			if (isPeerId(id)) {
				peers.add(id);
			}
		}
		return peers;
	}

	private boolean isPeerId(String id) {
		return MACHINE_ID.matcher(id).matches() && !id.equals(ownMachineId);
	}

	private List<String> peerDirectories() throws IOException {
		List<String> ids = new ArrayList<>();
		for (String name : shared.listRelative(FEEDS)) {
			if (isPeerId(name)) {
				ids.add(name);
			}
		}
		return ids;
	}

	/** Prüft alle Beobachtungen: Anspruch erschienen (übernehmen) oder abgelaufen (stillschweigend entfernen). */
	private void recheckWatched(Instant now) throws IOException {
		Iterator<Map.Entry<Watch, Instant>> iterator = watched.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Watch, Instant> entry = iterator.next();
			Watch watch = entry.getKey();
			Optional<byte[]> claim = shared.readClaimBytes(watch.unit(), watch.claimName());
			if (claim.isPresent()) {
				copyClaim(watch.unit(), watch.claimName(), watch.version(), claim.get());
				iterator.remove();
			} else if (expired(entry.getValue(), WATCH_LIMIT, now)) {
				iterator.remove();
			}
		}
	}

	private static boolean expired(Instant since, Duration limit, Instant now) {
		return !now.isBefore(since.plus(limit));
	}

	/**
	 * Liest die Listen eines Rechners ab Position+1, solange es welche gibt und Dateien übrig sind.
	 *
	 * @return die Zahl der Dateien, die dem Durchlauf noch bleiben
	 */
	private int readPeer(String peer, Instant now, int budget) throws IOException {
		int left = budget;
		long position = positions.position(peer);
		while (left > 0) {
			long seq = position + 1;
			Optional<byte[]> bytes = shared.readRelative(Feeds.feedPath(peer, seq));
			if (bytes.isEmpty()) {
				break;
			}
			left--;
			Feed feed = parseFeed(bytes.get(), peer, seq);
			if (feed == null) {
				// unbrauchbar: die Nummer überspringen, der Vollabgleich holt nach, was darin stand
				fullSyncNeeded = true;
			} else if (!process(peer, feed, now)) {
				break;
			}
			position = seq;
			positions.set(peer, seq);
			positionsDirty = true;
		}
		return left;
	}

	/** Die Liste, oder {@code null}, wenn sie unlesbar ist, ein unbekanntes Format hat oder nicht zu ihrem Pfad passt. */
	private static Feed parseFeed(byte[] bytes, String peer, long seq) {
		try {
			Feed feed = StoreCodec.readFeed(bytes);
			return feed.machine().equals(peer) && feed.seq() == seq ? feed : null;
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Verarbeitet eine Liste.
	 *
	 * @return {@code true}, wenn die Position vorrücken darf; {@code false}, wenn die Liste auf eine Versionsdatei wartet
	 */
	private boolean process(String peer, Feed feed, Instant now) throws IOException {
		String key = peer + "/" + feed.seq();
		List<FeedEntry> valid = new ArrayList<>();
		// die in diesem Durchlauf kopierten Versionsdateien, für den Abgleich mit dem Prüfwert des Anspruchs
		Map<VersionId, byte[]> copied = new HashMap<>();
		boolean versionMissing = false;
		for (FeedEntry entry : feed.entries()) {
			Optional<UnitKey> unit = UnitKey.parse(entry.unit());
			if (unit.isEmpty() || !isClaimName(entry.claim())) {
				fullSyncNeeded = true;
				continue;
			}
			valid.add(entry);
			VersionFetch fetch = fetchVersion(unit.get(), entry.version(), copied);
			if (fetch == VersionFetch.MISSING) {
				versionMissing = true;
			} else if (fetch == VersionFetch.COPIED) {
				// Lag der Anspruch schon lokal (über eine Beobachtung), war die Einheit bis jetzt "wartend"; jetzt ist
				// sie gültig, und niemand sonst meldet das
				changed.add(unit.get());
			}
		}
		if (versionMissing) {
			Instant since = waiting.computeIfAbsent(key, k -> now);
			if (!expired(since, WAIT_LIMIT, now)) {
				return false;
			}
			// zu lange gewartet: die Liste überspringen, der Vollabgleich holt die Dateien, wenn sie da sind
			fullSyncNeeded = true;
			waiting.remove(key);
			return true;
		}
		waiting.remove(key);
		for (FeedEntry entry : valid) {
			handleClaim(UnitKey.parse(entry.unit()).orElseThrow(), entry, copied.get(entry.version()), now);
		}
		return true;
	}

	private static boolean isClaimName(String name) {
		return "root".equals(name) || VersionId.isValid(name);
	}

	/** Was {@link #fetchVersion} mit einer Versionsdatei erreicht hat. */
	private enum VersionFetch {
		/** Die Datei lag schon lokal. */
		PRESENT,
		/** Die Datei wurde jetzt aus dem gemeinsamen Ordner kopiert. */
		COPIED,
		/** Die Datei fehlt lokal und im gemeinsamen Ordner. */
		MISSING
	}

	/**
	 * Stellt sicher, dass die Versionsdatei lokal liegt, und kopiert sie dazu, wenn sie fehlt.
	 *
	 * @param copied nimmt die Bytes einer jetzt kopierten Datei auf
	 */
	private VersionFetch fetchVersion(UnitKey unit, VersionId id, Map<VersionId, byte[]> copied) throws IOException {
		if (local.readVersionBytes(unit, id).isPresent()) {
			return VersionFetch.PRESENT;
		}
		Optional<byte[]> bytes = shared.readVersionBytes(unit, id);
		if (bytes.isEmpty()) {
			return VersionFetch.MISSING;
		}
		if (!local.writeVersion(unit, id, bytes.get())) {
			return VersionFetch.PRESENT;
		}
		copied.put(id, bytes.get());
		return VersionFetch.COPIED;
	}

	/**
	 * Holt die Anspruchsdatei des Eintrags oder beobachtet sie, wenn sie noch fehlt.
	 *
	 * @param copiedBytes die Bytes der Versionsdatei, wenn dieser Durchlauf sie kopiert hat; sonst {@code null}
	 */
	private void handleClaim(UnitKey unit, FeedEntry entry, byte[] copiedBytes, Instant now) throws IOException {
		Optional<byte[]> claim = shared.readClaimBytes(unit, entry.claim());
		if (claim.isPresent()) {
			if (copiedBytes != null) {
				checkCopied(unit, entry.version(), copiedBytes, claim.get());
			}
			copyClaim(unit, entry.claim(), entry.version(), claim.get());
			return;
		}
		checkContested(unit, entry.claim(), entry.version(), null);
		Watch watch = new Watch(unit, entry.version(), entry.claim());
		if (!watched.containsKey(watch)) {
			watched.put(watch, now);
			pending.add(new PendingSighting(unit, entry.version(), machineName(unit, entry.version())));
		}
	}

	/**
	 * Legt den Anspruch lokal an; war er neu, hat sich der gültige Stand der Einheit womöglich geändert. Lag lokal schon
	 * einer, wird verglichen (siehe {@link #checkContested}).
	 *
	 * @param listed die Version, die der fremde Eintrag für diesen Platz nennt
	 */
	private void copyClaim(UnitKey unit, String claimName, VersionId listed, byte[] bytes) throws IOException {
		if (local.createClaim(unit, claimName, bytes)) {
			changed.add(unit);
		} else {
			checkContested(unit, claimName, listed, bytes);
		}
	}

	/**
	 * Meldet den Platz als umstritten, wenn der lokale Anspruch der eigene ist und eine andere Version nennt als der
	 * fremde Eintrag (der Sync-Client hat die Niederlage vielleicht noch nicht übernommen), oder wenn der gemeinsame
	 * Anspruch eine andere Version nennt als der lokale (späte Niederlage). Ein lokal überschriebener Anspruch fiele
	 * sonst nie auf: Angelegt wird nur, was fehlt.
	 *
	 * @param sharedBytes der gemeinsame Anspruch; {@code null}, wenn er noch fehlt
	 */
	private void checkContested(UnitKey unit, String claimName, VersionId listed, byte[] sharedBytes) throws IOException {
		Optional<byte[]> localBytes = local.readClaimBytes(unit, claimName);
		Claim mine = localBytes.isPresent() ? parseClaim(localBytes.get()) : null;
		if (mine == null) {
			return;
		}
		Claim theirs = sharedBytes == null ? null : parseClaim(sharedBytes);
		boolean ownOther = ownMachineId.equals(mine.machine()) && !mine.version().equals(listed);
		boolean differs = theirs != null && !theirs.version().equals(mine.version());
		if (ownOther || differs) {
			contested.add(new FeedPoll.Contested(unit, claimName));
		}
	}

	/** Passt die kopierte Versionsdatei nicht zum Prüfwert des gemeinsamen Anspruchs, ist die gemeinsame Kopie beschädigt. */
	private void checkCopied(UnitKey unit, VersionId version, byte[] copiedBytes, byte[] sharedClaim) {
		Claim claim = parseClaim(sharedClaim);
		if (claim != null && claim.version().equals(version) && !StoreCodec.sha256(copiedBytes).equals(claim.sha256())) {
			damaged.add(unit);
		}
	}

	private static Claim parseClaim(byte[] bytes) {
		try {
			return StoreCodec.readClaim(bytes);
		} catch (IOException e) {
			return null;
		}
	}

	/** Der Rechnername aus der lokalen Versionsdatei; {@code null}, wenn er dort fehlt oder die Datei nicht lesbar ist. */
	private String machineName(UnitKey unit, VersionId id) throws IOException {
		Optional<byte[]> bytes = local.readVersionBytes(unit, id);
		if (bytes.isEmpty()) {
			return null;
		}
		try {
			return StoreCodec.readVersion(bytes.get()).machineName();
		} catch (IOException e) {
			return null;
		}
	}
}
