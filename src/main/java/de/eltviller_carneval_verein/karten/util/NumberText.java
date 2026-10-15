package de.eltviller_carneval_verein.karten.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Zahlen in deutscher Schreibweise für Anzeigen und Meldungen (ohne JavaFX). */
public final class NumberText {

	// DecimalFormat ist nicht threadsicher und teuer zu bauen; format läuft u. a. bei jeder Mausbewegung im Hallenplan
	private static final ThreadLocal<DecimalFormat> FORMAT = ThreadLocal
			.withInitial(() -> new DecimalFormat("0.####", DecimalFormatSymbols.getInstance(Locale.GERMANY)));

	private NumberText() {
	}

	/** Ohne überflüssige Nachkommastellen und ohne Tausenderpunkt: 1000.0 ergibt "1000", 0.5 ergibt "0,5". */
	public static String format(double value) {
		return FORMAT.get().format(value);
	}
}
