package de.eltviller_carneval_verein.karten.ui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.fxml.FXML;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.TextArea;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Window;

public class CashReconciliationController implements ContentController {

	/** Verfügbare Export-Formate für den Bilanz-/Kassenbericht, samt Anzeigename und Dateiendung. */
	private enum ExportFormat {
		CSV("Als CSV exportieren", "csv"), PDF("Als PDF exportieren", "pdf");

		private final String menuLabel;
		private final String extension;

		ExportFormat(String menuLabel, String extension) {
			this.menuLabel = menuLabel;
			this.extension = extension;
		}
	}

	private static final String PREF_KEY_LAST_EXPORT_FORMAT = "lastExportFormat";
	private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private final JsonTicketRepository repository = JsonTicketRepository.getInstance();
	private final Preferences prefs = Preferences.userNodeForPackage(CashReconciliationController.class);

	private Event selectedEvent;
	private Presentation selectedPres;
	private boolean editMode = false;
	private ExportFormat primaryExportFormat;

	// Verhindert, dass das programmatische Setzen von Spinner/Kommentarfeld (beim
	// Laden einer Vorstellung) fälschlich als Nutzereingabe interpretiert wird.
	private boolean updatingFromModel = false;

	// Zuletzt in refresh() berechnete Rohwerte (Cent/Anzahl), damit der Export
	// dieselben Zahlen nutzt wie die Anzeige, ohne formatierte Label-Strings
	// zurückparsen zu müssen.
	private long sollCashCents;
	private long sollCardCents;
	private long sollTransferCents;
	private int countCash;
	private int countCard;
	private int countTransfer;
	private long istCentsCache;

	@FXML
	private Label lblScopeHeader;
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
	private Label lblIstHint;
	@FXML
	private TextArea txtDifferenceComment;
	@FXML
	private SplitMenuButton btnExport;

	@FXML
	public void initialize() {
		actualCashSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 1_000_000.0, 0.0, 5));
		actualCashSpinner.setEditable(true);
		actualCashSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (updatingFromModel || selectedPres == null || newVal == null) {
				return;
			}
			selectedPres.setActualCashAmountDouble(newVal);
			istCentsCache = Math.round(newVal * 100.0);
			updateDifference(istCentsCache);
		});

		txtDifferenceComment.textProperty().addListener((obs, oldVal, newVal) -> {
			if (updatingFromModel || selectedPres == null) {
				return;
			}
			selectedPres.setCashDifferenceComment(newVal);
		});

		setupExportButton();
		applyEditMode();
	}

	private void setupExportButton() {
		for (ExportFormat format : ExportFormat.values()) {
			MenuItem item = new MenuItem(format.menuLabel);
			item.setOnAction(event -> {
				setPrimaryExportFormat(format);
				runExport(format);
			});
			btnExport.getItems().add(item);
		}

		ExportFormat lastUsed;
		try {
			lastUsed = ExportFormat.valueOf(prefs.get(PREF_KEY_LAST_EXPORT_FORMAT, ExportFormat.CSV.name()));
		} catch (IllegalArgumentException e) {
			lastUsed = ExportFormat.CSV;
		}
		setPrimaryExportFormat(lastUsed);

		btnExport.setOnAction(event -> runExport(primaryExportFormat));
	}

	private void setPrimaryExportFormat(ExportFormat format) {
		primaryExportFormat = format;
		btnExport.setText(format.menuLabel);
		prefs.put(PREF_KEY_LAST_EXPORT_FORMAT, format.name());
	}

	/**
	 * Ohne gewählte Vorstellung wird über alle Vorstellungen des Events
	 * aggregiert (Gesamtbilanz); mit gewählter Vorstellung nur über deren
	 * eigene Sitze.
	 */
	private List<Presentation> resolvePresentations() {
		if (selectedPres != null) {
			return List.of(selectedPres);
		}
		if (selectedEvent != null) {
			return selectedEvent.getPresentations();
		}
		return List.of();
	}

	private void refresh() {
		List<Presentation> presentations = resolvePresentations();
		if (presentations.isEmpty()) {
			clear();
			return;
		}

		sollCashCents = 0;
		sollCardCents = 0;
		sollTransferCents = 0;
		countCash = 0;
		countCard = 0;
		countTransfer = 0;
		long istCents = 0;

		for (Presentation pres : presentations) {
			istCents += Math.round(pres.getActualCashAmountDouble() * 100.0);

			for (Seat seat : pres.getSeats()) {
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
					sollCardCents += seat.getPrice();
					countCard++;
				}
				case TRANSFER -> {
					sollTransferCents += seat.getPrice();
					countTransfer++;
				}
				default -> {
				}
				}
			}
		}
		istCentsCache = istCents;

		if (selectedPres != null) {
			lblScopeHeader.setText("Soll-Einnahmen nach Zahlungsart – " + selectedPres.getName());
			lblIstHint.setText("");
		} else {
			int count = presentations.size();
			lblScopeHeader.setText("Soll-Einnahmen nach Zahlungsart – Gesamtbilanz (" + count + " Vorstellung" + (count == 1 ? "" : "en") + ")");
			lblIstHint.setText("Summe der Kasseninhalte der einzelnen Vorstellungen, hier nicht direkt bearbeitbar.");
		}

		lblCountCash.setText(String.valueOf(countCash));
		lblSollCash.setText(formatCents(sollCashCents));
		lblCountCard.setText(String.valueOf(countCard));
		lblSollCard.setText(formatCents(sollCardCents));
		lblCountTransfer.setText(String.valueOf(countTransfer));
		lblSollTransfer.setText(formatCents(sollTransferCents));
		lblCountTotal.setText(String.valueOf(countCash + countCard + countTransfer));
		lblSollTotal.setText(formatCents(sollCashCents + sollCardCents + sollTransferCents));

		updatingFromModel = true;
		actualCashSpinner.getValueFactory().setValue(istCents / 100.0);
		txtDifferenceComment.setText(selectedPres != null && selectedPres.getCashDifferenceComment() != null ? selectedPres.getCashDifferenceComment() : "");
		updatingFromModel = false;

		updateEditableControlsState();
		updateDifference(istCents);
	}

	private void updateDifference(long istCents) {
		long diffCents = istCents - sollCashCents;

		lblDifference.setText((diffCents > 0 ? "+" : "") + formatCents(diffCents));
		lblDifference.setTextFill(diffCents == 0 ? UiColors.STATUS_SUCCESS.getFxColor() : UiColors.STATUS_DANGER.getFxColor());
	}

	private void clear() {
		lblScopeHeader.setText("Soll-Einnahmen nach Zahlungsart");
		lblIstHint.setText("");
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
		txtDifferenceComment.setText("");
		updatingFromModel = false;

		sollCashCents = 0;
		sollCardCents = 0;
		sollTransferCents = 0;
		countCash = 0;
		countCard = 0;
		countTransfer = 0;
		istCentsCache = 0;
		lblDifference.setText("–");
		lblDifference.setTextFill(UiColors.TEXT_DARK.getFxColor());
		updateEditableControlsState();
	}

	private String formatCents(long cents) {
		return String.format(Locale.GERMANY, "%,.2f €", cents / 100.0);
	}

	/**
	 * Ist-Kasseninhalt und Differenz-Kommentar lassen sich nur für eine
	 * konkrete Vorstellung eintragen, nicht für die Gesamtbilanz. Der
	 * Export-Button bleibt unabhängig vom Bearbeitungsmodus nutzbar, solange
	 * überhaupt Daten gewählt sind.
	 */
	private void updateEditableControlsState() {
		boolean fieldsEditable = editMode && selectedPres != null;
		actualCashSpinner.setDisable(!fieldsEditable);
		txtDifferenceComment.setDisable(!fieldsEditable);
		btnExport.setDisable(selectedEvent == null);
	}

	private void applyEditMode() {
		updateEditableControlsState();
	}

	private void runExport(ExportFormat format) {
		if (selectedEvent == null) {
			return;
		}

		FileChooser fileChooser = new FileChooser();
		String baseName = selectedPres != null ? selectedEvent.getName() + "_" + selectedPres.getName() : selectedEvent.getName() + "_Gesamtbilanz";
		fileChooser.setInitialFileName(sanitizeFileName("Kassenbericht_" + baseName + "_" + LocalDate.now().format(FILE_DATE_FORMAT)) + "." + format.extension);
		fileChooser.getExtensionFilters().add(new ExtensionFilter(format.name() + "-Datei", "*." + format.extension));

		Window ownerWindow = btnExport.getScene() != null ? btnExport.getScene().getWindow() : null;
		File targetFile = fileChooser.showSaveDialog(ownerWindow);
		if (targetFile == null) {
			return;
		}

		try {
			switch (format) {
			case CSV -> writeCsv(targetFile);
			case PDF -> writePdf(targetFile);
			}
		} catch (IOException | RuntimeException e) {
			MainApp.showAlert("Fehler", "Export fehlgeschlagen: " + e.getMessage(), AlertType.ERROR);
		}
	}

	private String sanitizeFileName(String name) {
		return name.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	private void writeCsv(File file) throws IOException {
		StringBuilder csv = new StringBuilder();
		csv.append("Zahlungsart;Anzahl;Soll\n");
		csv.append("Bar;").append(countCash).append(';').append(formatCents(sollCashCents)).append('\n');
		csv.append("Karte;").append(countCard).append(';').append(formatCents(sollCardCents)).append('\n');
		csv.append("Überweisung;").append(countTransfer).append(';').append(formatCents(sollTransferCents)).append('\n');
		csv.append("Gesamt;").append(countCash + countCard + countTransfer).append(';').append(formatCents(sollCashCents + sollCardCents + sollTransferCents)).append('\n');

		if (selectedPres != null) {
			csv.append('\n');
			csv.append("Tatsächlicher Kasseninhalt (Bar);;").append(formatCents(istCentsCache)).append('\n');
			csv.append("Differenz zum Bar-Soll;;").append(formatCents(istCentsCache - sollCashCents)).append('\n');
			String comment = selectedPres.getCashDifferenceComment();
			csv.append("Kommentar;;").append(comment != null ? comment.replace(';', ',').replace("\n", " ") : "").append('\n');
		}

		Files.writeString(file.toPath(), csv.toString(), StandardCharsets.UTF_8);
	}

	private void writePdf(File file) throws IOException {
		Document document = new Document(PageSize.A4);
		try {
			PdfWriter.getInstance(document, new FileOutputStream(file));
			document.open();

			Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
			Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
			Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 11);
			Font boldCellFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);

			String scope = selectedPres != null ? selectedEvent.getName() + " – " + selectedPres.getName() : selectedEvent.getName() + " – Gesamtbilanz";
			Paragraph title = new Paragraph("Kassenbericht: " + scope, titleFont);
			title.setSpacingAfter(4f);
			document.add(title);

			Paragraph dateLine = new Paragraph("Erstellt am " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), cellFont);
			dateLine.setSpacingAfter(16f);
			document.add(dateLine);

			PdfPTable table = new PdfPTable(3);
			table.setWidthPercentage(100);
			table.setWidths(new float[] { 2f, 1f, 1.5f });

			addHeaderCell(table, "Zahlungsart", headerFont);
			addHeaderCell(table, "Anzahl", headerFont);
			addHeaderCell(table, "Soll", headerFont);

			addRow(table, "Bar", String.valueOf(countCash), formatCents(sollCashCents), cellFont);
			addRow(table, "Karte", String.valueOf(countCard), formatCents(sollCardCents), cellFont);
			addRow(table, "Überweisung", String.valueOf(countTransfer), formatCents(sollTransferCents), cellFont);
			addRow(table, "Gesamt", String.valueOf(countCash + countCard + countTransfer), formatCents(sollCashCents + sollCardCents + sollTransferCents), boldCellFont);

			document.add(table);

			if (selectedPres != null) {
				Paragraph spacer = new Paragraph(" ");
				spacer.setSpacingAfter(12f);
				document.add(spacer);

				document.add(new Paragraph("Tatsächlicher Kasseninhalt (Bar): " + formatCents(istCentsCache), cellFont));
				document.add(new Paragraph("Differenz zum Bar-Soll: " + formatCents(istCentsCache - sollCashCents), cellFont));

				String comment = selectedPres.getCashDifferenceComment();
				if (comment != null && !comment.isBlank()) {
					Paragraph commentParagraph = new Paragraph("Kommentar: " + comment, cellFont);
					commentParagraph.setSpacingBefore(8f);
					document.add(commentParagraph);
				}
			}
		} finally {
			document.close();
		}
	}

	private void addHeaderCell(PdfPTable table, String text, Font font) {
		PdfPCell cell = new PdfPCell(new Paragraph(text, font));
		cell.setHorizontalAlignment(Element.ALIGN_LEFT);
		cell.setPadding(6f);
		table.addCell(cell);
	}

	private void addRow(PdfPTable table, String label, String count, String soll, Font font) {
		addBodyCell(table, label, font);
		addBodyCell(table, count, font);
		addBodyCell(table, soll, font);
	}

	private void addBodyCell(PdfPTable table, String text, Font font) {
		PdfPCell cell = new PdfPCell(new Paragraph(text, font));
		cell.setPadding(6f);
		table.addCell(cell);
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
