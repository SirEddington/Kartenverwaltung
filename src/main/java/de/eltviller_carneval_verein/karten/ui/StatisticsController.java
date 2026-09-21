package de.eltviller_carneval_verein.karten.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.fxml.FXML;
import javafx.scene.SnapshotParameters;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

/**
 * Statistik-Ansicht der Abrechnung: aggregiert je Event (nicht je einzelner
 * Vorstellung) über alle im Repository vorhandenen Events. Rein lesend -
 * unabhängig von der Event-/Vorstellungsauswahl im Kopf der Abrechnung-Shell.
 */
public class StatisticsController implements ContentController, Exportable {

	private final JsonTicketRepository repository = JsonTicketRepository.getInstance();

	private List<EventStats> lastStats = List.of();

	@FXML
	private LineChart<String, Number> trendChart;
	@FXML
	private BarChart<String, Number> revenueChart;

	@FXML
	public void initialize() {
		refresh(false);
	}

	/**
	 * Baut beide Diagramme aus allen (ggf. inkl. archivierten) Events neu auf.
	 * Wird von der Abrechnung-Shell aufgerufen, sobald diese Ansicht sichtbar
	 * wird oder sich der Archiv-Filter ändert.
	 */
	public void refresh(boolean includeArchived) {
		lastStats = repository.loadEvents().stream()
				.filter(event -> includeArchived || !event.isArchived())
				.map(this::computeStats)
				.sorted(Comparator.comparing(EventStats::sortDate))
				.toList();

		long maxRevenueCents = lastStats.stream().mapToLong(EventStats::revenueCents).max().orElse(0);
		double avgRevenueCents = lastStats.stream().mapToLong(EventStats::revenueCents).average().orElse(0);

		XYChart.Series<String, Number> occupancySeries = new XYChart.Series<>();
		occupancySeries.setName("Auslastung (%)");
		XYChart.Series<String, Number> pctOfMaxSeries = new XYChart.Series<>();
		pctOfMaxSeries.setName("Einnahmen (% vom Maximum)");
		XYChart.Series<String, Number> pctOfAvgSeries = new XYChart.Series<>();
		pctOfAvgSeries.setName("Einnahmen (% vom Durchschnitt)");
		XYChart.Series<String, Number> revenueSeries = new XYChart.Series<>();
		revenueSeries.setName("Einnahmen (€)");

		for (EventStats s : lastStats) {
			occupancySeries.getData().add(new XYChart.Data<>(s.name(), s.occupancyPct()));
			pctOfMaxSeries.getData().add(new XYChart.Data<>(s.name(), maxRevenueCents > 0 ? (s.revenueCents() * 100.0 / maxRevenueCents) : 0));
			pctOfAvgSeries.getData().add(new XYChart.Data<>(s.name(), avgRevenueCents > 0 ? (s.revenueCents() * 100.0 / avgRevenueCents) : 0));
			revenueSeries.getData().add(new XYChart.Data<>(s.name(), s.revenueCents() / 100.0));
		}

		trendChart.getData().setAll(occupancySeries, pctOfMaxSeries, pctOfAvgSeries);
		revenueChart.getData().setAll(revenueSeries);
	}

	/**
	 * Auslastung/Einnahmen zählen nur bezahlte Sitze (dieselbe Definition wie
	 * im Kassenabgleich: {@link PaymentStatus#isPaid()}), nicht nur reservierte.
	 * Das Event-Datum wird aus der frühesten Vorstellung abgeleitet, da Event
	 * selbst kein eigenes Datum hat.
	 */
	private EventStats computeStats(Event event) {
		long revenueCents = 0;
		int soldSeats = 0;
		int totalSeats = 0;
		LocalDate earliest = null;

		for (Presentation pres : event.getPresentations()) {
			if (pres.getDate() != null && (earliest == null || pres.getDate().isBefore(earliest))) {
				earliest = pres.getDate();
			}
			for (Seat seat : pres.getSeats()) {
				totalSeats++;
				PaymentStatus status = seat.getPaymentStatus();
				if (status != null && status.isPaid()) {
					revenueCents += seat.getPrice();
					soldSeats++;
				}
			}
		}

		double occupancyPct = totalSeats > 0 ? (soldSeats * 100.0 / totalSeats) : 0;
		return new EventStats(event.getName(), revenueCents, occupancyPct, earliest != null ? earliest : LocalDate.MAX);
	}

	private record EventStats(String name, long revenueCents, double occupancyPct, LocalDate sortDate) {
	}

	@Override
	public String suggestedFileBaseName() {
		return "Statistik";
	}

	@Override
	public void exportCsv(File file) throws IOException {
		StringBuilder csv = new StringBuilder();
		csv.append("Event;Auslastung (%);Einnahmen (€);Einnahmen (% vom Maximum);Einnahmen (% vom Durchschnitt)\n");

		long maxRevenueCents = lastStats.stream().mapToLong(EventStats::revenueCents).max().orElse(0);
		double avgRevenueCents = lastStats.stream().mapToLong(EventStats::revenueCents).average().orElse(0);

		for (EventStats s : lastStats) {
			double pctOfMax = maxRevenueCents > 0 ? (s.revenueCents() * 100.0 / maxRevenueCents) : 0;
			double pctOfAvg = avgRevenueCents > 0 ? (s.revenueCents() * 100.0 / avgRevenueCents) : 0;
			csv.append(s.name().replace(';', ',')).append(';')
					.append(formatNumber(s.occupancyPct())).append(';')
					.append(formatNumber(s.revenueCents() / 100.0)).append(';')
					.append(formatNumber(pctOfMax)).append(';')
					.append(formatNumber(pctOfAvg)).append('\n');
		}

		Files.writeString(file.toPath(), csv.toString(), StandardCharsets.UTF_8);
	}

	@Override
	public void exportPdf(File file) throws IOException {
		Document document = new Document(PageSize.A4.rotate());
		try {
			PdfWriter.getInstance(document, new FileOutputStream(file));
			document.open();

			Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
			Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
			Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 10);

			Paragraph title = new Paragraph("Statistik: Auslastung & Einnahmen je Event", titleFont);
			title.setSpacingAfter(4f);
			document.add(title);

			Paragraph dateLine = new Paragraph("Erstellt am " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), cellFont);
			dateLine.setSpacingAfter(12f);
			document.add(dateLine);

			addChartImage(document, trendChart);
			addChartImage(document, revenueChart);

			PdfPTable table = new PdfPTable(5);
			table.setWidthPercentage(100);
			table.setSpacingBefore(12f);
			table.setWidths(new float[] { 2.5f, 1f, 1f, 1.2f, 1.2f });

			addHeaderCell(table, "Event", headerFont);
			addHeaderCell(table, "Auslastung", headerFont);
			addHeaderCell(table, "Einnahmen", headerFont);
			addHeaderCell(table, "% vom Maximum", headerFont);
			addHeaderCell(table, "% vom Durchschnitt", headerFont);

			long maxRevenueCents = lastStats.stream().mapToLong(EventStats::revenueCents).max().orElse(0);
			double avgRevenueCents = lastStats.stream().mapToLong(EventStats::revenueCents).average().orElse(0);

			for (EventStats s : lastStats) {
				double pctOfMax = maxRevenueCents > 0 ? (s.revenueCents() * 100.0 / maxRevenueCents) : 0;
				double pctOfAvg = avgRevenueCents > 0 ? (s.revenueCents() * 100.0 / avgRevenueCents) : 0;
				addBodyCell(table, s.name(), cellFont);
				addBodyCell(table, formatNumber(s.occupancyPct()) + " %", cellFont);
				addBodyCell(table, formatEuro(s.revenueCents()), cellFont);
				addBodyCell(table, formatNumber(pctOfMax) + " %", cellFont);
				addBodyCell(table, formatNumber(pctOfAvg) + " %", cellFont);
			}

			document.add(table);
		} finally {
			document.close();
		}
	}

	/** Bettet einen Snapshot des Diagramms als Bild ein (JavaFX-Charts selbst sind keine PDF-Elemente). */
	private void addChartImage(Document document, javafx.scene.Node chart) throws IOException {
		WritableImage snapshot = chart.snapshot(new SnapshotParameters(), null);
		BufferedImage bufferedImage = toBufferedImage(snapshot);

		ByteArrayOutputStream pngBytes = new ByteArrayOutputStream();
		ImageIO.write(bufferedImage, "png", pngBytes);

		com.lowagie.text.Image pdfImage = com.lowagie.text.Image.getInstance(pngBytes.toByteArray());
		float maxWidth = document.getPageSize().getWidth() - document.leftMargin() - document.rightMargin();
		if (pdfImage.getWidth() > maxWidth) {
			pdfImage.scaleToFit(maxWidth, pdfImage.getHeight());
		}
		pdfImage.setSpacingAfter(8f);
		document.add(pdfImage);
	}

	/** Manuelle Pixel-Kopie statt SwingFXUtils, um keine zusätzliche javafx-swing-Abhängigkeit zu brauchen. */
	private BufferedImage toBufferedImage(WritableImage fxImage) {
		int width = (int) Math.round(fxImage.getWidth());
		int height = (int) Math.round(fxImage.getHeight());
		BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		PixelReader pixelReader = fxImage.getPixelReader();
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				bufferedImage.setRGB(x, y, pixelReader.getArgb(x, y));
			}
		}
		return bufferedImage;
	}

	private void addHeaderCell(PdfPTable table, String text, Font font) {
		PdfPCell cell = new PdfPCell(new Paragraph(text, font));
		cell.setHorizontalAlignment(Element.ALIGN_LEFT);
		cell.setPadding(5f);
		table.addCell(cell);
	}

	private void addBodyCell(PdfPTable table, String text, Font font) {
		PdfPCell cell = new PdfPCell(new Paragraph(text, font));
		cell.setPadding(5f);
		table.addCell(cell);
	}

	private String formatNumber(double value) {
		return String.format(Locale.GERMANY, "%,.1f", value);
	}

	private String formatEuro(long cents) {
		return String.format(Locale.GERMANY, "%,.2f €", cents / 100.0);
	}

	@Override
	public void setEvent(Event event) {
		// Absichtlich kein Trigger für refresh(): die Statistik hängt nicht an der
		// Event-Auswahl im Kopf, sondern aggregiert immer über alle Events. Die
		// Abrechnung-Shell ruft refresh(boolean) explizit beim Anzeigen und bei
		// Änderung des Archiv-Filters auf.
	}

	@Override
	public void setPresentation(Presentation presentation) {
		// Nicht relevant für die Statistik.
	}

	@Override
	public void save() {
		// Nichts zu speichern, rein abgeleitete Ansicht.
	}

	@Override
	public void filter(String query) {
		// Kein Suchfeld-Bezug in dieser Ansicht.
	}

	@Override
	public void setEditMode(boolean enabled) {
		// Keine editierbaren Felder in der Statistik.
	}
}
