package de.eltviller_carneval_verein.karten.store;

import java.util.List;
import java.util.Set;

/**
 * Der gültige Stand einer Einheit, wie ihn {@link ChainResolver} aus den Dateien ermittelt.
 *
 * @param unit      die Einheit
 * @param status    ob die Kette vollständig lesbar ist oder warten oder gesperrt ist
 * @param chain     die gültige Kette, ältestes Glied zuerst; bei {@code WAITING} und den Sperren endet sie vor der
 *                  Stelle, an der es nicht weitergeht
 * @param pending   die eigenen, noch nicht beanspruchten Versionen auf dem Ende der Kette (bei {@code LOCKED_DAMAGED}
 *                  auch auf der beschädigten Version, an der sie endet: das Zurücksetzen), in der Reihenfolge, in der
 *                  sie hochzuladen sind (Vorgänger vor Nachfolger)
 * @param lost      die eigenen Versionen, die nicht auf dem Ende der Kette aufbauen und deshalb verloren sind; eine
 *                  Liste je Strecke, älteste Version zuerst; gehört eine Version zu mehreren Strecken (eigene
 *                  Verzweigung), steht sie nur in der ersten, und eine spätere Liste kann mit einer Version
 *                  beginnen, deren erster Vorgänger in einer früheren Liste steht
 * @param blockedAt die Version, an der die Kette endet ({@code WAITING}: die fehlende, {@code LOCKED_FORMAT}: die
 *                  mit unbekanntem Format, {@code LOCKED_DAMAGED}: die beschädigte oder bei einem Kreis die
 *                  wiederholte); {@code null} bei {@code OK} und bei einer unlesbaren Anspruchsdatei
 * @param claimed   jede Version, die eine Anspruchsdatei auf dem Weg der Kette nennt, auch die beschädigten und
 *                  übersprungenen (sie haben ihren Platz bekommen, stehen aber nicht in {@code chain}); bei
 *                  {@code WAITING} und den Sperren auch die Version, an der die Kette endet
 */
public record UnitState(UnitKey unit, Status status, List<Version> chain, List<Version> pending,
		List<List<VersionId>> lost, VersionId blockedAt, Set<VersionId> claimed) {

	/** Zustand der Kette einer Einheit. */
	public enum Status {
		/** Die Kette ist vollständig und unbeschädigt. */
		OK,
		/** Eine Anspruchsdatei nennt eine Version, die lokal noch fehlt; die Kette endet vorläufig davor. */
		WAITING,
		/** Eine Datei der Kette ist beschädigt und ohne Ersatz; die Kette endet davor. */
		LOCKED_DAMAGED,
		/** Eine Datei der Kette hat ein unbekanntes Format (neueres Programm); die Kette endet davor. */
		LOCKED_FORMAT
	}

	/** Macht die Listen unveränderlich. */
	public UnitState {
		chain = List.copyOf(chain);
		pending = List.copyOf(pending);
		lost = lost.stream().map(List::copyOf).toList();
		claimed = Set.copyOf(claimed);
	}

	/** Das letzte Glied der gültigen Kette, oder {@code null}, wenn die Kette leer ist. */
	public Version head() {
		return chain.isEmpty() ? null : chain.get(chain.size() - 1);
	}

	/**
	 * Der aktuelle Stand für den eigenen Rechner: die letzte ausstehende Version, sonst {@link #head()}. Gibt es
	 * mehrere ausstehende Strecken, ist es das letzte Element der letzten Strecke.
	 */
	public Version current() {
		return pending.isEmpty() ? head() : pending.get(pending.size() - 1);
	}

	/** Ob der aktuelle Stand vorläufig ist, also eigene Versionen noch auf ihren Anspruch warten. */
	public boolean provisional() {
		return !pending.isEmpty();
	}
}
