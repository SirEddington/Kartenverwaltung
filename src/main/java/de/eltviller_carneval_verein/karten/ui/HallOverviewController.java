package de.eltviller_carneval_verein.karten.ui;

import java.util.List;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatStatus;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.fxml.FXML;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.text.Text;

public class HallOverviewController implements ContentController{

	private final JsonTicketRepository repository = new JsonTicketRepository();
	private Event selectedEvent;
	private Presentation selectedPres;
	private boolean editMode = false;

	@FXML
	private Pane hallPane;
	
	public void renderHall() {

		hallPane.getChildren().clear();
		
		if (selectedPres == null) {
            return;
        }
		
		// 1. Automatische Positionierung für Tische & Stühle ohne manuelle Position anwenden
		applyDefaultPositions();
		
		// 2. Zeichnen der Hallen-Objekte
		for (HallObject hallObject : selectedPres.getHallObjects()) {
			drawHallObject(hallObject);
		}

		// 3. Zeichnen der Tische und Stühle
		for (Table table : selectedPres.getTables()) {
			drawTable(table);
		}
	}
	
	/**
     * Berechnet die Standardpositionen für Tische und Stühle, 
     * sofern diese noch nicht manuell verschoben wurden.
     */
    private void applyDefaultPositions() {
        List<Table> tables = selectedPres.getTables();
        if (tables == null || tables.isEmpty()) {
            return;
        }

        int tablesPerColumn = 7;//selectedPres.getTableRows();   // Max. Tische pro Spalte
        double startX = 60;        // Start-X im Pane
        double startY = 40;        // Start-Y im Pane
        double defaultWidth = 70;  // Standard-Tischbreite
        double defaultHeight = 150; // Standard-Tischhöhe
        double spacingX = 200;     // Abstand zwischen den Tisch-Spalten
        double spacingY = 150;     // Abstand zwischen den Tisch-Zeilen

        for (int i = 0; i < tables.size(); i++) {
            Table table = tables.get(i);

            // Fallback für Tischgrößen, falls diese 0 sind
            if (table.getWidth() <= 0) table.setWidth(defaultWidth);
            if (table.getHeight() <= 0) table.setHeight(defaultHeight);

            // --- TISCH POSITIONIERUNG ---
            if (!table.isManualPos()) {
                int col = i / tablesPerColumn; // Spalte (0, 1, 2...)
                int row = i % tablesPerColumn; // Zeile (0 bis 6)

                table.setPosX(startX + col * spacingX);
                table.setPosY(startY + row * spacingY);
            }

            // --- STUHL POSITIONIERUNG ---
            if (table.getSeats() != null && !table.getSeats().isEmpty()) {
                applyDefaultSeatPositions(table);
            }
        }
    }
    
    /**
     * Platziert die Stühle paarweise links und rechts am Tisch.
     */
    private void applyDefaultSeatPositions(Table table) {
        List<Seat> seats = table.getSeats();
        int totalSeats = seats.size();

        // Anzahl der Reihen (Paare) am Tisch
        int rows = (int) Math.ceil(totalSeats / 2.0);

        double marginY = 20; // Abstand der äußeren Stühle zur Ober-/Unterkante des Tischs
        double usableHeight = Math.max(table.getHeight() - (2 * marginY), 20);
        double stepY = rows > 1 ? usableHeight / (rows - 1) : 0;

        for (int j = 0; j < totalSeats; j++) {
            Seat seat = seats.get(j);

            if (!seat.isManualPos()) {
                boolean isLeft = (j % 2 == 0); // Index 0, 2, 4... -> Links | Index 1, 3, 5... -> Rechts
                int rowIndex = j / 2;          // 0, 0, 1, 1, 2, 2...

                // X-Position: Links oder Rechts vom Tisch mit 15px Abstand
                double posX = isLeft ? (table.getPosX() - 15) : (table.getPosX() + table.getWidth() + 15);

                // Y-Position: Bei 1 Stuhl-Paar zentriert, sonst gleichmäßig verteilt
                double posY = (rows == 1) 
                        ? (table.getPosY() + (table.getHeight() / 2.0))
                        : (table.getPosY() + marginY + (rowIndex * stepY));

                seat.setPosX(posX);
                seat.setPosY(posY);
            }
        }
    }

	private void drawTable(Table table) {
		Node tableNode = createTableNode(table);
        hallPane.getChildren().add(tableNode);

        if (table.getSeats() != null) {
            for (Seat seat : table.getSeats()) {
                drawSeat(seat);
            }
        }
	}

	private Node createTableNode(Table table) {
		Rectangle tableShape = new Rectangle(table.getPosX(), table.getPosY(), table.getWidth(), table.getHeight());
        tableShape.setArcWidth(10);
        tableShape.setArcHeight(10);

        tableShape.setFill(Color.LIGHTGRAY);
        tableShape.setStroke(Color.DARKGRAY);

        Text tableLabel = new Text("Tisch " + table.getTableNumber());
        tableLabel.setX(table.getPosX() + 10);
        tableLabel.setY(table.getPosY() + 20);

        return new Group(tableShape, tableLabel);
	}

	private void drawSeat(Seat seat) {
		Circle seatCircle = new Circle(seat.getPosX(), seat.getPosY(), 10);
        seatCircle.setFill(getColorForSeatStatus(seat.getStatus()));
        seatCircle.setStroke(Color.BLACK);

        Tooltip.install(seatCircle, new Tooltip("Seat " + seat.getSeatNumber() + " (" + seat.getStatus() + ")"));
        seatCircle.setOnMouseClicked(e -> handleSeatClick(seat));

        hallPane.getChildren().add(seatCircle);
	}

	private void drawHallObject(HallObject hallObject) {
		Node hallObjectNode = createHallObjectNode(hallObject);
		hallPane.getChildren().add(hallObjectNode);
	}

	private Node createHallObjectNode(HallObject hallObject) {
		// Java 21 Switch Expression für Shapes
		Shape hallObjectShape = switch (hallObject.getShape()) {
		case CIRCLE -> {
			double radius = hallObject.getWidth() / 2.0;
			yield new Circle(hallObject.getPosX() + radius, hallObject.getPosY() + radius, radius);
		}
		case RECTANGLE -> {
			Rectangle rect = new Rectangle(hallObject.getPosX(), hallObject.getPosY(), hallObject.getWidth(), hallObject.getHeight());
			rect.setArcWidth(10);
			rect.setArcHeight(10);
			yield rect;
		}
		};

		hallObjectShape.setFill(Color.LIGHTGRAY);
		hallObjectShape.setStroke(Color.DARKGRAY);

		Text hallObjectLabel = new Text(hallObject.getName());
		hallObjectLabel.setX(hallObject.getPosX() + 10);
		hallObjectLabel.setY(hallObject.getPosY() + 20);

		return new Group(hallObjectShape, hallObjectLabel);
	}

	private Color getColorForSeatStatus(SeatStatus status) {
		if (status == null)
			return Color.GREEN;
		return switch (status) {
		case FREE -> Color.LIGHTGREEN;
		case RESERVED -> Color.ORANGE;
		case SOLD -> Color.INDIANRED;
		case BLOCKED -> Color.GRAY;
		};
	}

	private void handleSeatClick(Seat seat) {
		System.out.println("Clicked seat: " + seat.getSeatNumber());
	}

	private void applyEditMode() {
		// ToDo
	}

	@Override
	public void setEvent(Event event) {
		this.selectedEvent = event;
		selectedPres = null;
	}

	@Override
	public void setPresentation(Presentation presentation) {
		this.selectedPres = presentation;
		if (selectedPres != null) {
			this.selectedEvent = presentation.getParent();
			renderHall();
		} else {
			selectedEvent = null;
		}
	}

	@Override
	public void save() {
		// Aktuellen Stand speichern
		repository.saveEvent(selectedEvent);
	}

	@Override
	public void filter(String query) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setEditMode(boolean enabled) {
		editMode = enabled;
		applyEditMode();
	}
}