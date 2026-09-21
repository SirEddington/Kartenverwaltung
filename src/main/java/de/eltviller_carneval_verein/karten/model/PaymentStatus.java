package de.eltviller_carneval_verein.karten.model;

public enum PaymentStatus {
    NONE("Offen", false),
    CASH("Bar", true),
    CARD("Karte", true),
    TRANSFER("Überweisung", true);

    private final String displayName;
    private final boolean paid;

    PaymentStatus(String displayName, boolean paid) {
        this.displayName = displayName;
        this.paid = paid;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Gibt true zurück, wenn das Ticket bezahlt wurde (egal mit welcher Zahlungsmethode) */
    public boolean isPaid() {
        return paid;
    }
}
