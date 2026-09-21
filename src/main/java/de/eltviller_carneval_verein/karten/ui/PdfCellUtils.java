package de.eltviller_carneval_verein.karten.ui;

import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;

/**
 * Gemeinsame Zellen-Bausteine für die PdfPTable-Berichte in
 * {@link CashReconciliationController} und {@link StatisticsController} -
 * beide bauen eine einfache Kopf-/Datenzeilen-Tabelle mit demselben Aussehen.
 */
final class PdfCellUtils {

	private PdfCellUtils() {
	}

	static PdfPCell headerCell(String text, Font font) {
		PdfPCell cell = new PdfPCell(new Paragraph(text, font));
		cell.setHorizontalAlignment(Element.ALIGN_LEFT);
		cell.setPadding(6f);
		return cell;
	}

	static PdfPCell bodyCell(String text, Font font) {
		PdfPCell cell = new PdfPCell(new Paragraph(text, font));
		cell.setPadding(6f);
		return cell;
	}
}
