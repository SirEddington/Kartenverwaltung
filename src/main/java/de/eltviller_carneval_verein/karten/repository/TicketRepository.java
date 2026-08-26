package de.eltviller_carneval_verein.karten.repository;

import de.eltviller_carneval_verein.karten.model.Event;
import java.util.List;

public interface TicketRepository {
	
	List<Event> loadEvents();
	
	void saveEvents(List<Event> events);
}
