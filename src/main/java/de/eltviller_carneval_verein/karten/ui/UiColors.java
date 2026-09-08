package de.eltviller_carneval_verein.karten.ui;

import javafx.scene.paint.Color;

public enum UiColors {
	// --- Neutrale Farben ---
	WHITE("#FFFFFF"), GRAY("#999999"), BACKGROUND_LIGHT("#F8F9FA"), TEXT_DARK("#1A1A1A"),

	// --- ECV Vereinsfarben (Corporate Identity) ---
	ECV_BLAU("#2C2F88"), // Hauptfarbe für UI & Schriftzüge
	ECV_ROT("#E83B3B"), // Akzent & Danger
	ECV_GELB("#FFE000"), // Vereins-Gelb
	ECV_GOLD("#BE9F56"), // Jubiläen / Besonderes

	// --- Status-Farben ---
	STATUS_SUCCESS("#2ECC71"), // Emerald (Bezahlt / Frei)
	STATUS_WARNING(ECV_GELB), // "#F39C12" Golden Orange (Offen / Reserviert)
	STATUS_DANGER(ECV_ROT), // Strawberry Red / ECV-Rot (Storniert / Gesperrt)

	// --- Sitzplatz-Farben ---
	SEAT_FREE(STATUS_SUCCESS), SEAT_RESEREVED(STATUS_WARNING), SEAT_SOLD(STATUS_DANGER), SEAT_BLOCKED(GRAY);

	private final String hex;
	private final Color color;

	UiColors(String hex) {
		this.hex = hex;
		this.color = Color.web(hex);
	}

	UiColors(UiColors uiColor) {
		this.hex = uiColor.getHex();
		this.color = Color.web(uiColor.getHex());
	}

	/** Gibt das JavaFX-Color-Objekt zurück (z.B. für Canvas, Shape.setFill, etc.) */
	public Color getFxColor() {
		return color;
	}

	/** Gibt den Hex-Code zurück (z.B. "#4CAF50") */
	public String getHex() {
		return hex;
	}

	/** CSS-Style für Hintergrundfarben */
	public String toBackgroundStyle() {
		return "-fx-background-color: " + hex + ";";
	}

	/** CSS-Style für Textfarben */
	public String toTextStyle() {
		return "-fx-text-fill: " + hex + ";";
	}
}
