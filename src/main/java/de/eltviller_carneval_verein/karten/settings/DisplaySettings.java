package de.eltviller_carneval_verein.karten.settings;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Das Menü "Anzeige" der Saalansicht und des Hallen-Editors, wie es in {@code settings.json} steht (KARV-76): die
 * Beschriftungen des Plans, die barrierefreie Sitzdarstellung (KARV-26), der Hinweis zur Auswahl am Mauszeiger
 * (KARV-194) samt Lage und Ausweichen am Rand (KARV-197), die Platzierung der Kaufzusammenfassung im Kundenfenster und ob diese Auswahl
 * beim nächsten Start wieder gilt ({@code restoreAtStart}, Standard an). Wann geschrieben und gelesen wird, regelt
 * {@code DisplayPreferences}. Lage und Platzierung stehen als Text darin, damit dieses Paket die Oberfläche nicht kennt
 * und ein unbekannter Wert aus einer neueren Version die Datei nicht unlesbar macht.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DisplaySettings {

	/** Die Platzierung, wenn keine oder eine unbekannte gespeichert ist. */
	public static final String DEFAULT_SUMMARY_PLACEMENT = "AUTO";
	/** Die Lage des Hinweises am Mauszeiger, wenn keine oder eine unbekannte gespeichert ist. */
	public static final String DEFAULT_SELECTION_HINT_PLACEMENT = "ABOVE";
	/** Die Ordnung der Gruppen in der Kartentabelle, wenn keine oder eine unbekannte gespeichert ist. */
	public static final String DEFAULT_TICKET_GROUP_ORDER = "VALUE";

	private boolean restoreAtStart = true;
	private boolean rows = true;
	private boolean tables = true;
	private boolean seatNumbers = true;
	private boolean hallObjects = true;
	private boolean seatPatterns = true;
	private boolean selectionHint = true;
	private String selectionHintPlacement = DEFAULT_SELECTION_HINT_PLACEMENT;
	private boolean selectionHintAvoidEdges = true;
	private String summaryPlacement = DEFAULT_SUMMARY_PLACEMENT;
	private List<String> ticketGrouping = List.of();
	private String ticketGroupOrder = DEFAULT_TICKET_GROUP_ORDER;
	private boolean ticketGroupingNested = true;

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

	/**
	 * Ob die Verkaufsansicht Anzahl und Preis der gewählten Karten am Mauszeiger zeigt (KARV-194). Fehlt das Feld (Datei
	 * von vor 1.1.1), ist es an.
	 */
	public boolean isSelectionHint() {
		return selectionHint;
	}

	public void setSelectionHint(boolean selectionHint) {
		this.selectionHint = selectionHint;
	}

	/**
	 * Der Name der Lage des Hinweises am Mauszeiger (z. B. {@code "BELOW"}, KARV-197); nie {@code null}, kann aber aus einer
	 * neueren Version unbekannt sein. Fehlt das Feld, gilt {@link #DEFAULT_SELECTION_HINT_PLACEMENT}.
	 */
	public String getSelectionHintPlacement() {
		return selectionHintPlacement;
	}

	/** {@code null} gilt als {@link #DEFAULT_SELECTION_HINT_PLACEMENT}. */
	public void setSelectionHintPlacement(String selectionHintPlacement) {
		this.selectionHintPlacement = selectionHintPlacement == null ? DEFAULT_SELECTION_HINT_PLACEMENT : selectionHintPlacement;
	}

	/**
	 * Ob der Hinweis am Mauszeiger am Rand auf die andere Seite des Zeigers ausweicht (KARV-197); sonst behält er seine
	 * Seite und wird nur an den Rand geschoben. Fehlt das Feld, ist es an.
	 */
	public boolean isSelectionHintAvoidEdges() {
		return selectionHintAvoidEdges;
	}

	public void setSelectionHintAvoidEdges(boolean selectionHintAvoidEdges) {
		this.selectionHintAvoidEdges = selectionHintAvoidEdges;
	}

	/** Der Name der Platzierung (z. B. {@code "AUTO"}); nie {@code null}, kann aber aus einer neueren Version unbekannt sein. */
	public String getSummaryPlacement() {
		return summaryPlacement;
	}

	/** {@code null} gilt als {@link #DEFAULT_SUMMARY_PLACEMENT}. */
	public void setSummaryPlacement(String summaryPlacement) {
		this.summaryPlacement = summaryPlacement == null ? DEFAULT_SUMMARY_PLACEMENT : summaryPlacement;
	}

	/**
	 * Die Namen der Spalten, nach denen die Kartentabelle gruppiert ist, von außen nach innen (KARV-193); nie {@code null},
	 * ohne Gruppierung leer. Die Namen stehen als Text darin, damit dieses Paket die Oberfläche nicht kennt; ein
	 * unbekannter Name aus einer neueren Version bleibt hier stehen, bis {@code DisplayPreferences} die Liste neu schreibt.
	 */
	public List<String> getTicketGrouping() {
		return ticketGrouping;
	}

	/** {@code null} gilt als keine Gruppierung; die Liste wird kopiert, {@code null}-Einträge (aus einer Hand-Änderung) entfallen. */
	public void setTicketGrouping(List<String> ticketGrouping) {
		this.ticketGrouping = ticketGrouping == null ? List.of() : ticketGrouping.stream().filter(Objects::nonNull).toList();
	}

	/**
	 * Der Name der Ordnung der Gruppen (z. B. {@code "SIZE"}, KARV-193); nie {@code null}, kann aber aus einer neueren
	 * Version unbekannt sein. Fehlt das Feld, gilt {@link #DEFAULT_TICKET_GROUP_ORDER}.
	 */
	public String getTicketGroupOrder() {
		return ticketGroupOrder;
	}

	/** {@code null} gilt als {@link #DEFAULT_TICKET_GROUP_ORDER}. */
	public void setTicketGroupOrder(String ticketGroupOrder) {
		this.ticketGroupOrder = ticketGroupOrder == null ? DEFAULT_TICKET_GROUP_ORDER : ticketGroupOrder;
	}

	/**
	 * Ob die Gruppen der Kartentabelle bei mehreren Spalten ineinander verschachtelt sind (KARV-193); sonst stehen sie auf
	 * einer Ebene. Fehlt das Feld, ist es an.
	 */
	public boolean isTicketGroupingNested() {
		return ticketGroupingNested;
	}

	public void setTicketGroupingNested(boolean ticketGroupingNested) {
		this.ticketGroupingNested = ticketGroupingNested;
	}
}
