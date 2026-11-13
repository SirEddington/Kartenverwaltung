package de.eltviller_carneval_verein.karten.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Das Menü "Anzeige" der Saalansicht und des Hallen-Editors, wie es in {@code settings.json} steht (KARV-76): die
 * Beschriftungen des Plans, die barrierefreie Sitzdarstellung (KARV-26), die Platzierung der Kaufzusammenfassung im
 * Kundenfenster und ob diese Auswahl beim
 * nächsten Start wieder gilt ({@code restoreAtStart}, Standard an). Wann geschrieben und gelesen wird, regelt
 * {@code DisplayPreferences}. Die Platzierung steht als Text darin, damit dieses Paket die Oberfläche nicht kennt und
 * ein unbekannter Wert aus einer neueren Version die Datei nicht unlesbar macht.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DisplaySettings {

	/** Die Platzierung, wenn keine oder eine unbekannte gespeichert ist. */
	public static final String DEFAULT_SUMMARY_PLACEMENT = "AUTO";

	private boolean restoreAtStart = true;
	private boolean rows = true;
	private boolean tables = true;
	private boolean seatNumbers = true;
	private boolean hallObjects = true;
	private boolean seatPatterns = true;
	private String summaryPlacement = DEFAULT_SUMMARY_PLACEMENT;

	public DisplaySettings() {
	}

	/** Ob der Start die gespeicherte Auswahl übernimmt; sonst beginnt er mit allem sichtbar und "Automatisch". */
	public boolean isRestoreAtStart() {
		return restoreAtStart;
	}

	public void setRestoreAtStart(boolean restoreAtStart) {
		this.restoreAtStart = restoreAtStart;
	}

	public boolean isRows() {
		return rows;
	}

	public void setRows(boolean rows) {
		this.rows = rows;
	}

	public boolean isTables() {
		return tables;
	}

	public void setTables(boolean tables) {
		this.tables = tables;
	}

	public boolean isSeatNumbers() {
		return seatNumbers;
	}

	public void setSeatNumbers(boolean seatNumbers) {
		this.seatNumbers = seatNumbers;
	}

	public boolean isHallObjects() {
		return hallObjects;
	}

	public void setHallObjects(boolean hallObjects) {
		this.hallObjects = hallObjects;
	}

	/**
	 * Ob der Saalplan den Sitzstatus barrierefrei zeigt, also nicht nur über die Farbe (KARV-26). Fehlt das Feld (Datei
	 * von vor KARV-26), ist es an.
	 */
	public boolean isSeatPatterns() {
		return seatPatterns;
	}

	public void setSeatPatterns(boolean seatPatterns) {
		this.seatPatterns = seatPatterns;
	}

	/** Der Name der Platzierung (z. B. {@code "AUTO"}); nie {@code null}, kann aber aus einer neueren Version unbekannt sein. */
	public String getSummaryPlacement() {
		return summaryPlacement;
	}

	/** {@code null} gilt als {@link #DEFAULT_SUMMARY_PLACEMENT}. */
	public void setSummaryPlacement(String summaryPlacement) {
		this.summaryPlacement = summaryPlacement == null ? DEFAULT_SUMMARY_PLACEMENT : summaryPlacement;
	}
}
