package de.eltviller_carneval_verein.karten.store;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Die Regel, nach der der Store eine gelesene Version als „inhaltlich zu neu“ erkennt: Sie ist ganz und hat das richtige
 * Dateiformat, enthält aber etwas, das dieses Programm nicht sicher verarbeiten kann (etwa einen Zahlstatus aus einer
 * neueren Version). Der Store kennt die Fachregel nicht; sie kommt von außen.
 */
public interface ContentGate {

	/** Das Tor, das nie „neuer“ meldet. Nur für Tests des Stores und den Konstruktor ohne Tor. */
	ContentGate NONE = new ContentGate() {
		@Override
		public boolean isNewer(UnitKey unit, ObjectNode payload) {
			return false;
		}

		@Override
		public String rulesId() {
			return "none";
		}
	};

	/**
	 * Ob der Inhalt einer Einheit von einem neueren Programm stammt. Wird nie für Löschversionen gefragt.
	 *
	 * @param unit    die Einheit, zu der die Version gehört
	 * @param payload die Daten der Version
	 * @return {@code true}, wenn dieses Programm den Inhalt nicht sicher verarbeiten kann
	 */
	boolean isNewer(UnitKey unit, ObjectNode payload);

	/**
	 * Eine Kennung der Regel samt allem, wovon sie abhängt. Ändert sich die Regel, ändert sich die Kennung, und
	 * gespeicherte Ergebnisse (der Startindex) werden neu bestimmt.
	 */
	String rulesId();
}
