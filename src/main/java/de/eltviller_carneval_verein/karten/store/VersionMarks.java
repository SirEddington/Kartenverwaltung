package de.eltviller_carneval_verein.karten.store;

/**
 * Was eine Version über ihre Entstehung festhält, neben Inhalt und Vermerk (KARV-93). Alle Angaben sind optional.
 *
 * @param movedTo    die Buchung, die der erste Vorgänger dieser Version trug, lebt nach diesem Speichervorgang in dieser
 *                   Einheit (die Version nimmt sie hier weg: Löschung oder Inhalt mit anderer Buchung); {@code null}, wenn
 *                   sie nicht umzog
 * @param movedFrom  die Buchung, die diese Version trägt, kam in diesem Speichervorgang aus dieser Einheit; {@code null},
 *                   wenn sie nicht hereinzog
 * @param resolution ob eine Auflösung die Version geschrieben hat (Zusammenführung oder Zusatz-Änderung); sonst hat ein
 *                   Speichern der App sie geschrieben
 */
public record VersionMarks(UnitKey movedTo, UnitKey movedFrom, boolean resolution) {

	/** Keine Angaben: eine gewöhnliche Version ohne Umzug. */
	public static final VersionMarks NONE = new VersionMarks(null, null, false);

	/** Eine Version, die eine Auflösung geschrieben hat. */
	public static final VersionMarks RESOLUTION = new VersionMarks(null, null, true);

	/** Ob keine Angabe gesetzt ist. */
	public boolean isNone() {
		return movedTo == null && movedFrom == null && !resolution;
	}

	/** Dieselben Angaben mit diesem Ziel. */
	public VersionMarks withMovedTo(UnitKey target) {
		return new VersionMarks(target, movedFrom, resolution);
	}

	/** Dieselben Angaben mit dieser Herkunft. */
	public VersionMarks withMovedFrom(UnitKey source) {
		return new VersionMarks(movedTo, source, resolution);
	}

	/** Dieselben Angaben, geschrieben von einer Auflösung. */
	public VersionMarks withResolution() {
		return new VersionMarks(movedTo, movedFrom, true);
	}
}
