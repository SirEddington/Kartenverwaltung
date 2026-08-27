package de.eltviller_carneval_verein.karten.model;

import java.util.List;

public interface EventObject {

	public List<Presentation> getPresentations();
	public List<Table> getTables();
	public List<Seat> getSeats();
}
