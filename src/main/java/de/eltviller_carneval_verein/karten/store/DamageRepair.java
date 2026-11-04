package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Heilt beschädigte Versions- und Anspruchsdateien aus geprüften Kopien (Stufe 1 der Heilung). Jede Versionsdatei gibt
 * es auf mehreren Rechnern und im gemeinsamen Ordner; die Anspruchsdatei hält ihren SHA-256-Prüfwert fest. Damit lässt
 * sich jede Kopie prüfen, und eine beschädigte wird durch eine gute ersetzt. Jeder Rechner mit guter Kopie heilt so den
 * gemeinsamen Ordner, alle anderen heilen sich beim nächsten Lesen daraus.
 * <p>
 * Ersetzt wird über {@link VersionStore#replaceDamaged} (und damit {@link FileLayer#replaceDamaged}; im gemeinsamen Ordner
 * trägt die temporäre Datei die Kennung des Rechners): Die beschädigte Datei bleibt als {@code <Name>.corrupt-<Zeitstempel>}
 * daneben liegen und wird nie überschrieben oder gelöscht. Ohne gemeinsamen Ordner (Einzelbetrieb) gibt es keine zweite
 * Kopie; eine beschädigte lokale bleibt dann ohne Ersatz.
 */
public final class DamageRepair {

	/** Ergebnis einer Heilung. */
	public enum Outcome {
		/**
		 * Es war nichts zu heilen: die Kopien sind gut, beide Anspruchsdateien lesbar (auch wenn sie verschiedene Versionen
		 * nennen: das ist eine Niederlage, keine Beschädigung), eine Datei hat ein neueres Format, oder es fehlt nur die
		 * gemeinsame Kopie bzw. die lokale Anspruchsdatei (das holt der Abgleich). Eine fehlende lokale Versionsdatei
		 * neben einer guten gemeinsamen wird dagegen neu angelegt: {@link #REPAIRED_LOCAL}.
		 */
		NOT_DAMAGED,
		/** Die lokale Kopie wurde durch die gemeinsame ersetzt oder, wenn sie fehlte, neu angelegt. */
		REPAIRED_LOCAL,
		/** Die gemeinsame Kopie wurde durch die lokale ersetzt. */
		REPAIRED_SHARED,
		/** Eine Kopie ist beschädigt, und es gibt keine geprüfte gute (Stufe 2: Zurücksetzen). */
		NO_GOOD_COPY
	}

	/**
	 * Was die Heilung getan hat, mit den Pfaden für die Meldung SYS-007.
	 *
	 * @param outcome     das Ergebnis
	 * @param replaced    die Datei, die ersetzt oder (bei fehlender lokaler Versionsdatei) neu angelegt wurde; sonst
	 *                    {@code null}
	 * @param corruptCopy der Pfad, unter dem die beschädigte Datei daneben liegt; {@code null}, wenn nichts umbenannt wurde
	 *                    (nichts zu heilen, keine gute Kopie, oder die Datei fehlte)
	 */
	public record Result(Outcome outcome, Path replaced, Path corruptCopy) {

		private static Result none(Outcome outcome) {
			return new Result(outcome, null, null);
		}
	}

	private static final String JSON = ".json";
	private static final DateTimeFormatter CORRUPT_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	/** Zustand einer Kopie: nicht vorhanden, gut, beschädigt oder mit neuerem Format (nicht beschädigt, nicht lesbar). */
	private enum State {
		MISSING, GOOD, DAMAGED, UNKNOWN_FORMAT
	}

	/** Eine Kopie mit ihrem Zustand; {@code bytes} ist {@code null}, wenn sie fehlt. */
	private record Copy(State state, byte[] bytes) {
	}

	private final VersionStore local;
	private final VersionStore shared;
	private final FileLayer files;
	private final Clock clock;

	/**
	 * @param local  der lokale Bestand
	 * @param shared der gemeinsame Bestand; {@code null} im Einzelbetrieb
	 * @param files  die Dateischicht, über die ersetzt wird
	 * @param clock  die Uhr für den Zeitstempel im Namen der beschädigten Kopie
	 */
	public DamageRepair(VersionStore local, VersionStore shared, FileLayer files, Clock clock) {
		this.local = local;
		this.shared = shared;
		this.files = files;
		this.clock = clock;
	}

	/**
	 * Prüft die lokale und die gemeinsame Kopie einer Versionsdatei gegen den Prüfwert der Anspruchsdatei und ersetzt eine
	 * beschädigte durch die gute der anderen Seite. Eine fehlende lokale Kopie wird dabei neu angelegt; eine fehlende
	 * gemeinsame ist kein Fall der Heilung (der Abgleich lädt sie hoch).
	 *
	 * @param claim die Anspruchsdatei, die die Version nennt und ihren Prüfwert enthält
	 * @return was getan wurde; {@link Outcome#NO_GOOD_COPY}, wenn eine Kopie beschädigt ist und keine gute da ist.
	 *         {@link Result#replaced()} ist die ersetzte oder neu angelegte Datei, {@link Result#corruptCopy()} der Pfad
	 *         der beschädigten Datei daneben (bei einer neu angelegten fehlt er)
	 */
	public Result repairVersion(UnitKey unit, Claim claim) throws IOException {
		VersionId id = claim.version();
		Copy localCopy = checkVersion(local.readVersionBytes(unit, id), claim.sha256());
		if (shared == null) {
			return Result.none(localCopy.state() == State.DAMAGED ? Outcome.NO_GOOD_COPY : Outcome.NOT_DAMAGED);
		}
		Copy sharedCopy = checkVersion(shared.readVersionBytes(unit, id), claim.sha256());
		if (localCopy.state() == State.GOOD && sharedCopy.state() == State.DAMAGED) {
			return replace(shared, shared.resolve(versionRel(unit, id)), localCopy.bytes(), Outcome.REPAIRED_SHARED);
		}
		if (sharedCopy.state() == State.GOOD && localCopy.state() == State.DAMAGED) {
			return replace(local, local.resolve(versionRel(unit, id)), sharedCopy.bytes(), Outcome.REPAIRED_LOCAL);
		}
		if (sharedCopy.state() == State.GOOD && localCopy.state() == State.MISSING) {
			// Lokal fehlt die Datei nur: anlegen (war sie inzwischen da, ist nichts zu tun)
			return local.writeVersion(unit, id, sharedCopy.bytes())
					? new Result(Outcome.REPAIRED_LOCAL, local.resolve(versionRel(unit, id)), null)
					: Result.none(Outcome.NOT_DAMAGED);
		}
		boolean anyDamaged = localCopy.state() == State.DAMAGED || sharedCopy.state() == State.DAMAGED;
		boolean anyGood = localCopy.state() == State.GOOD || sharedCopy.state() == State.GOOD;
		return Result.none(anyDamaged && !anyGood ? Outcome.NO_GOOD_COPY : Outcome.NOT_DAMAGED);
	}

	/**
	 * Ersetzt eine unlesbare Anspruchsdatei durch die lesbare der anderen Seite. Sind beide lesbar, ist nichts zu heilen,
	 * auch wenn sie verschiedene Versionen nennen: Das ist eine Niederlage (Abgleich, Hochladen und späte Niederlage
	 * entscheiden darüber), keine Beschädigung. Eine fehlende lokale Anspruchsdatei ist ebenfalls kein Fall der Heilung
	 * (der Abgleich kopiert sie), ebenso eine Datei mit höherem Format (neueres Programm).
	 *
	 * @param claimName {@code root} oder die ID des Vorgängers
	 * @return was getan wurde; {@link Outcome#NO_GOOD_COPY}, wenn keine Seite lesbar ist. {@link Result#replaced()} ist
	 *         die ersetzte Datei, {@link Result#corruptCopy()} der Pfad der unlesbaren Datei daneben
	 */
	public Result repairClaim(UnitKey unit, String claimName) throws IOException {
		Copy localCopy = checkClaim(local.readClaimBytes(unit, claimName));
		if (shared == null) {
			return Result.none(localCopy.state() == State.DAMAGED ? Outcome.NO_GOOD_COPY : Outcome.NOT_DAMAGED);
		}
		Copy sharedCopy = checkClaim(shared.readClaimBytes(unit, claimName));
		if (localCopy.state() == State.UNKNOWN_FORMAT || sharedCopy.state() == State.UNKNOWN_FORMAT) {
			return Result.none(Outcome.NOT_DAMAGED);
		}
		if (localCopy.state() == State.GOOD && sharedCopy.state() == State.DAMAGED) {
			return replace(shared, shared.resolve(claimRel(unit, claimName)), localCopy.bytes(), Outcome.REPAIRED_SHARED);
		}
		if (sharedCopy.state() == State.GOOD && localCopy.state() == State.DAMAGED) {
			return replace(local, local.resolve(claimRel(unit, claimName)), sharedCopy.bytes(), Outcome.REPAIRED_LOCAL);
		}
		boolean anyDamaged = localCopy.state() == State.DAMAGED || sharedCopy.state() == State.DAMAGED;
		boolean anyGood = localCopy.state() == State.GOOD || sharedCopy.state() == State.GOOD;
		return Result.none(anyDamaged && !anyGood ? Outcome.NO_GOOD_COPY : Outcome.NOT_DAMAGED);
	}

	/**
	 * Ersetzt die beschädigte Datei des Bestands durch die gute Fassung und behält sie unter einem neuen
	 * {@code .corrupt-}-Namen. Der Bestand schreibt die temporäre Datei: im gemeinsamen Ordner mit der Kennung des Rechners.
	 */
	private Result replace(VersionStore store, Path file, byte[] good, Outcome outcome) throws IOException {
		String corruptName = corruptName(file);
		store.replaceDamaged(file, good, corruptName);
		return new Result(outcome, file, file.resolveSibling(corruptName));
	}

	/** Gut ist eine vorhandene Versionsdatei, deren SHA-256 dem Prüfwert der Anspruchsdatei entspricht. */
	private static Copy checkVersion(Optional<byte[]> bytes, String expectedSha256) {
		if (bytes.isEmpty()) {
			return new Copy(State.MISSING, null);
		}
		boolean good = StoreCodec.sha256(bytes.get()).equals(expectedSha256);
		return new Copy(good ? State.GOOD : State.DAMAGED, bytes.get());
	}

	/** Lesbar ist eine vorhandene Anspruchsdatei, die {@link StoreCodec#readClaim} versteht. */
	private static Copy checkClaim(Optional<byte[]> bytes) {
		if (bytes.isEmpty()) {
			return new Copy(State.MISSING, null);
		}
		try {
			StoreCodec.readClaim(bytes.get());
			return new Copy(State.GOOD, bytes.get());
		} catch (UnknownFormatException e) {
			return new Copy(State.UNKNOWN_FORMAT, bytes.get());
		} catch (IOException e) {
			return new Copy(State.DAMAGED, bytes.get());
		}
	}

	/**
	 * Der Name für die beschädigte Kopie: {@code <Dateiname>.corrupt-<yyyy-MM-dd_HH-mm-ss>}, bei Namensgleichheit mit
	 * Zusatz {@code -2}, {@code -3} …, damit keine vorhandene Kopie überschrieben wird.
	 */
	private String corruptName(Path file) {
		String base = file.getFileName() + ".corrupt-" + LocalDateTime.now(clock).format(CORRUPT_STAMP);
		String name = base;
		for (int attempt = 2; files.exists(file.resolveSibling(name)); attempt++) {
			name = base + "-" + attempt;
		}
		return name;
	}

	private static String versionRel(UnitKey unit, VersionId id) {
		return unit.relativePath() + "/" + id + JSON;
	}

	private static String claimRel(UnitKey unit, String claimName) {
		return unit.relativePath() + "/claims/" + claimName + JSON;
	}
}
