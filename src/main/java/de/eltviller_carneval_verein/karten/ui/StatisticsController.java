package de.eltviller_carneval_verein.karten.ui;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.fxml.FXML;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;

/**
 * Statistik-Ansicht der Abrechnung: aggregiert je Event (nicht je einzelner
 * Vorstellung) über alle im Repository vorhandenen Events. Rein lesend -
 * unabhängig von der Event-/Vorstellungsauswahl im Kopf der Abrechnung-Shell.
 */
public class StatisticsController implements ContentController {

	private final JsonTicketRepository repository = JsonTicketRepository.getInstance();

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
		List<EventStats> stats = repository.loadEvents().stream()
				.filter(event -> includeArchived || !event.isArchived())
				.map(this::computeStats)
				.sorted(Comparator.comparing(EventStats::sortDate))
				.toList();

		long maxRevenueCents = stats.stream().mapToLong(EventStats::revenueCents).max().orElse(0);
		double avgRevenueCents = stats.stream().mapToLong(EventStats::revenueCents).average().orElse(0);

		XYChart.Series<String, Number> occupancySeries = new XYChart.Series<>();
		occupancySeries.setName("Auslastung (%)");
		XYChart.Series<String, Number> pctOfMaxSeries = new XYChart.Series<>();
		pctOfMaxSeries.setName("Einnahmen (% vom Maximum)");
		XYChart.Series<String, Number> pctOfAvgSeries = new XYChart.Series<>();
		pctOfAvgSeries.setName("Einnahmen (% vom Durchschnitt)");
		XYChart.Series<String, Number> revenueSeries = new XYChart.Series<>();
		revenueSeries.setName("Einnahmen (€)");

		for (EventStats s : stats) {
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
