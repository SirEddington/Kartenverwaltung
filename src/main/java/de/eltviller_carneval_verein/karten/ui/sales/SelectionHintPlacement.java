package de.eltviller_carneval_verein.karten.ui.sales;

/**
 * Wo der Hinweis zur Auswahl am Mauszeiger steht (Menü "Anzeige", KARV-197), siehe {@link SelectionHint#place}. Ob er
 * überhaupt zu sehen ist, entscheidet der Schalter daneben ({@code DisplayPreferences.selectionHintProperty()}).
 */
public enum SelectionHintPlacement {

	ABOVE("Über dem Zeiger"), BELOW("Unter dem Zeiger");

	private final String label;

	SelectionHintPlacement(String label) {
		this.label = label;
	}

	/** Die Beschriftung im Menü. */
	public String label() {
		return label;
	}
}
