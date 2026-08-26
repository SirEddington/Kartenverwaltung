package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;

public class SeatDTO {
	private final Event event;
	private final Presentation presentation;
	private final Table table;
	private final Seat seat;
	
	public SeatDTO(Event event, Presentation presentation, Table table, Seat seat) {
		this.event = event;
		this.presentation = presentation;
		this.table = table;
		this.seat = seat;
	}
	
	public Event getEvent() { 
		return event;
	}
    public Presentation getPresentation() {
    	return presentation;
    }
    public Table getTable() {
    	return table;
    }
    public Seat getSeat() {
    	return seat;
    }
}
