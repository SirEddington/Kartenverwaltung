package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;

public interface ContentController {
    // Wird aufgerufen, wenn sich das gewählte Event im Header ändert
    void setEvent(Event event);

    // Wird aufgerufen, wenn sich die gewählte Vorstellung im Header ändert
    void setPresentation(Presentation presentation);

    // Wird aufgerufen, wenn der Benutzer auf "Speichern" klickt
    void save();
    
    // Optional: Suchbegriff an den Inhalt weiterleiten
    void filter(String query);
    
    // Aktiviert/Deaktiviert die Bearbeitung
    void setEditMode(boolean enabled);
}