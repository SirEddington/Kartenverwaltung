module Kartenverwaltung {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.controlsfx.controls;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires com.github.librepdf.openpdf;
    requires java.prefs;
    requires java.desktop;

    // Hauptpaket für JavaFX (für MainApp)
    opens de.eltviller_carneval_verein.karten to javafx.fxml, javafx.graphics;
    exports de.eltviller_carneval_verein.karten;

    // Model-Paket für Jackson & JavaFX TableView Properties
    opens de.eltviller_carneval_verein.karten.model to com.fasterxml.jackson.databind, javafx.base;
    exports de.eltviller_carneval_verein.karten.model;

    // UI-Pakete für FXML-Reflection öffnen (je Unterpaket, seit die Controller
    // nach Zuständigkeit aufgeteilt wurden statt alle in einem Paket zu liegen)
    opens de.eltviller_carneval_verein.karten.ui to javafx.fxml;
    exports de.eltviller_carneval_verein.karten.ui;

    opens de.eltviller_carneval_verein.karten.ui.menu to javafx.fxml;
    exports de.eltviller_carneval_verein.karten.ui.menu;

    opens de.eltviller_carneval_verein.karten.ui.event to javafx.fxml;
    exports de.eltviller_carneval_verein.karten.ui.event;

    opens de.eltviller_carneval_verein.karten.ui.hall to javafx.fxml;
    exports de.eltviller_carneval_verein.karten.ui.hall;

    opens de.eltviller_carneval_verein.karten.ui.sales to javafx.fxml;
    exports de.eltviller_carneval_verein.karten.ui.sales;

    opens de.eltviller_carneval_verein.karten.ui.accounting to javafx.fxml;
    exports de.eltviller_carneval_verein.karten.ui.accounting;

    // Repository-Paket exportieren
    exports de.eltviller_carneval_verein.karten.repository;

    // Plausibilitätsprüfungen (ohne JavaFX, von UI und Tests genutzt)
    exports de.eltviller_carneval_verein.karten.validation;
}