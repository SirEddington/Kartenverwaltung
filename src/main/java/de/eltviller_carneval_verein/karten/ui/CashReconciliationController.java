package de.eltviller_carneval_verein.karten.ui;

import java.util.Locale;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;

public class CashReconciliationController implements ContentController {

	private final JsonTicketRepository repository = JsonTicketRepository.getInstance();

	private Event selectedEvent;
	private Presentation selectedPres;
	private boolean editMode = false;

	// Verhindert, dass das programmatische Setzen des Spinner-Werts (beim Laden
	// einer Vorstellung) fälschlich als Nutzereingabe interpretiert wird.
	private boolean updatingFromModel = false;
	private long sollCashCents;

	@FXML
	private Label lblCountCash;
	@FXML
	private Label lblSollCash;
	@FXML
	private Label lblCountCard;
	@FXML
	private Label lblSollCard;
	@FXML
	private Label lblCountTransfer;
	@FXML
	private Label lblSollTransfer;
	@FXML
	private Label lblCountTotal;
	@FXML
	private Label lblSollTotal;
	@FXML
	private Spinner<Double> actualCashSpinner;
	@FXML
	private Label lblDifference;

	@FXML
	public void initialize() {
		actualCashSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 1_000_000.0, 0.0, 5));
		actualCashSpinner.setEditable(true);
		actualCashSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (updatingFromModel || selectedPres == null || newVal == null) {
				return;
			}
			selectedPres.setActualCashAmountDouble(newVal);
			updateDifference();
		});

		applyEditMode();
	}

	private void refresh() {
		if (selectedPres == null) {
			clear();
			return;
		}

		long sollCard = 0;
		long sollTransfer = 0;
		int countCash = 0;
		int countCard = 0;
		int countTransfer = 0;
		sollCashCents = 0;

		for (Seat seat : selectedPres.getSeats()) {
			PaymentStatus status = seat.getPaymentStatus();
			if (status == null || !status.isPaid()) {
				continue;
			}
			switch (status) {
			case CASH -> {
				sollCashCents += seat.getPrice();
				countCash++;
			}
			case CARD -> {
				sollCard += seat.getPrice();
				countCard++;
			}
			case TRANSFER -> {
				sollTransfer += seat.getPrice();
				countTransfer++;
			}
			default -> {
			}
			}
		}

		lblCountCash.setText(String.valueOf(countCash));
		lblSollCash.setText(formatCents(sollCashCents));
		lblCountCard.setText(String.valueOf(countCard));
		lblSollCard.setText(formatCents(sollCard));
		lblCountTransfer.setText(String.valueOf(countTransfer));
		lblSollTransfer.setText(formatCents(sollTransfer));
		lblCountTotal.setText(String.valueOf(countCash + countCard + countTransfer));
		lblSollTotal.setText(formatCents(sollCashCents + sollCard + sollTransfer));

		updatingFromModel = true;
		actualCashSpinner.getValueFactory().setValue(selectedPres.getActualCashAmountDouble());
		updatingFromModel = false;

		updateDifference();
	}

	private void updateDifference() {
		long istCents = Math.round(selectedPres.getActualCashAmountDouble() * 100.0);
		long diffCents = istCents - sollCashCents;

		lblDifference.setText((diffCents > 0 ? "+" : "") + formatCents(diffCents));
		lblDifference.setTextFill(diffCents == 0 ? UiColors.STATUS_SUCCESS.getFxColor() : UiColors.STATUS_DANGER.getFxColor());
	}

	private void clear() {
		lblCountCash.setText("0");
		lblSollCash.setText(formatCents(0));
		lblCountCard.setText("0");
		lblSollCard.setText(formatCents(0));
		lblCountTransfer.setText("0");
		lblSollTransfer.setText(formatCents(0));
		lblCountTotal.setText("0");
		lblSollTotal.setText(formatCents(0));

		updatingFromModel = true;
		actualCashSpinner.getValueFactory().setValue(0.0);
		updatingFromModel = false;

		sollCashCents = 0;
		lblDifference.setText("–");
		lblDifference.setTextFill(UiColors.TEXT_DARK.getFxColor());
	}

	private String formatCents(long cents) {
		return String.format(Locale.GERMANY, "%,.2f €", cents / 100.0);
	}

	private void applyEditMode() {
		actualCashSpinner.setDisable(!editMode);
	}

	@Override
	public void setEvent(Event event) {
		this.selectedEvent = event;
		this.selectedPres = null;
		refresh();
	}

	@Override
	public void setPresentation(Presentation presentation) {
		this.selectedPres = presentation;
		if (selectedPres != null) {
			this.selectedEvent = presentation.getParent();
		}
		refresh();
	}

	@Override
	public void save() {
		if (selectedEvent != null) {
			repository.saveEvent(selectedEvent);
		}
	}

	@Override
	public void filter(String query) {
		// Kein Suchfeld-Bezug in dieser Ansicht
	}

	@Override
	public void setEditMode(boolean enabled) {
		editMode = enabled;
		applyEditMode();
	}
}
