package de.eltviller_carneval_verein.karten.ui.hall;

import java.io.IOException;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.ui.ContentController;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

public class HallShellController implements ContentController {
	private final JsonHallRepository hallRepository = JsonHallRepository.getInstance();
	
    @FXML private TextField searchField;
    @FXML private RadioButton btnTableView;
    @FXML private RadioButton btnHallView;
    @FXML private StackPane contentArea;
    @FXML private Label lblHeader;

    private ContentController activeContentController;
    private Hall currentHall;
    private boolean editMode = false;
    
    @FXML
    public void initialize() {
		// Freitext-Suche auf den geladenen Event-Daten
		searchField.textProperty().addListener((obs, oldVal, newValue) -> {
			activeContentController.filter((newValue == null) ? "" : newValue.toLowerCase().trim());
		});
		
		showHallManagementView();
    }
    
    @FXML
    public void showHallManagementView() {
    	
    }

    public void loadContentView(String fxmlPath) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Node view = loader.load();

            // Aktiven Inhalts-Controller merken
            this.activeContentController = loader.getController();

            // Inhalt im mittleren Bereich austauschen
            contentArea.getChildren().setAll(view);

            // Aktuelles Event und  Vorstellung direkt an den neuen Inhalt übergeben
            activeContentController.setHall(currentHall);
        } catch (IOException e) {
            e.printStackTrace();
            MainApp.showAlert("Fehler", "Ansicht konnte nicht geladen werden: " + e.getMessage(), AlertType.ERROR);
        }
    }

	@Override
	public void setEvent(Event event) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setPresentation(Presentation presentation) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setHall(Hall hall) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void save() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void filter(String query) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setEditMode(boolean enabled) {
		// TODO Auto-generated method stub
		
	}
}
