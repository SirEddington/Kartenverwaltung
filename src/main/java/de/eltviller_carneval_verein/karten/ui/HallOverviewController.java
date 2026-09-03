package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatStatus;
import de.eltviller_carneval_verein.karten.model.Table;
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

public class HallOverviewController {

	@FXML
	private Pane hallPane;

	public void renderHall(Presentation pres) {

		hallPane.getChildren().clear();
		for (HallObject hallObject : pres.getHallObjects()) {
			drawHallObject(hallObject);
		}

		for (Table table : pres.getTables()) {
			drawTable(table);
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
}