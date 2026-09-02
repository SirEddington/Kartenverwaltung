package de.eltviller_carneval_verein.karten.ui;

import java.util.List;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatStatus;
import de.eltviller_carneval_verein.karten.model.Table;
import javafx.fxml.FXML;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;

public class HallOverviewController {

	@FXML
	private Pane hallPane;

	@FXML
	public void initialize() {
		// Initialization logic
	}

	/**
	 * Renders all tables and seats for the selected performance.
	 */
	public void renderHall(List<Table> tables) {
		hallPane.getChildren().clear();

		for (Table table : tables) {
			drawTable(table);
		}
	}

	private void drawTable(Table table) {
		// Render table shape
		Rectangle tableShape = new Rectangle(table.getPosX(), table.getPosY(), table.getWidth(), table.getHeight());
		tableShape.setFill(Color.LIGHTGRAY);
		tableShape.setStroke(Color.DARKGRAY);
		tableShape.setArcWidth(10);
		tableShape.setArcHeight(10);

		// Table label
		Text tableLabel = new Text(table.getPosX() + 10, table.getPosY() + 25, "Table " + table.getTableNumber());

		hallPane.getChildren().addAll(tableShape, tableLabel);

		// Render associated seats
		if (table.getSeats() != null) {
			for (Seat seat : table.getSeats()) {
				drawSeat(seat);
			}
		}
	}

	private void drawSeat(Seat seat) {
		Circle seatShape = new Circle(seat.getPosX(), seat.getPosY(), 12);
		seatShape.setFill(getColorForStatus(seat.getStatus()));
		seatShape.setStroke(Color.BLACK);

		Tooltip tooltip = new Tooltip("Seat " + seat.getSeatNumber() + " (" + seat.getStatus() + ")");
		Tooltip.install(seatShape, tooltip);

		seatShape.setOnMouseClicked(e -> handleSeatClick(seat));

		hallPane.getChildren().add(seatShape);
	}

	private Color getColorForStatus(SeatStatus status) {
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
		System.out.println("Seat clicked: " + seat.getSeatNumber());
	}
}