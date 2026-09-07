package de.eltviller_carneval_verein.karten.model;

import de.eltviller_carneval_verein.karten.ui.UiColors;

public enum PaymentStatus {
    NONE("Offen", UiColors.STATUS_WARNING, UiColors.TEXT_DARK, false),
    CASH("Bar", UiColors.STATUS_SUCCESS, UiColors.WHITE, true),
    CARD("Karte", UiColors.STATUS_SUCCESS, UiColors.WHITE, true),
    TRANSFER("Überweisung", UiColors.STATUS_SUCCESS, UiColors.WHITE, true);

    private final String displayName;
    private final UiColors backgroundColor;
    private final UiColors textColor;
    private final boolean paid;

    PaymentStatus(String displayName, UiColors backgroundColor, UiColors textColor, boolean paid) {
        this.displayName = displayName;
        this.backgroundColor = backgroundColor;
        this.textColor = textColor;
        this.paid = paid;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UiColors getBackgroundColor() {
        return backgroundColor;
    }

    public UiColors getTextColor() {
        return textColor;
    }

    /** Gibt true zurück, wenn das Ticket bezahlt wurde (egal mit welcher Zahlungsmethode) */
    public boolean isPaid() {
        return paid;
    }

    /** Gibt den vollständigen CSS-Style für Badges oder Tabellenzellen zurück */
    public String toBadgeStyle() {
        return backgroundColor.toBackgroundStyle() + " " + textColor.toTextStyle();
    }
}