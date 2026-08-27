module Kartenverwaltung {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.controlsfx.controls;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.annotation;

    // Hauptpaket für JavaFX (für MainApp)
    opens de.eltviller_carneval_verein.karten to javafx.fxml, javafx.graphics;
    exports de.eltviller_carneval_verein.karten;

    // Model-Paket für Jackson & JavaFX TableView Properties
    opens de.eltviller_carneval_verein.karten.model to com.fasterxml.jackson.databind, javafx.base;
    exports de.eltviller_carneval_verein.karten.model;

    // UI-Paket für FXML-Reflection öffnen
    opens de.eltviller_carneval_verein.karten.ui to javafx.fxml;
    exports de.eltviller_carneval_verein.karten.ui;

    // Repository-Paket exportieren
    exports de.eltviller_carneval_verein.karten.repository;
}