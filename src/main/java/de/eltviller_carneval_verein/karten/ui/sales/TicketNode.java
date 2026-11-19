package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;

/** Ein Knoten des Gruppenbaums; Buchungen haben keine Kinder. */
public record TicketNode(TicketRow row, List<TicketNode> children) {
}
