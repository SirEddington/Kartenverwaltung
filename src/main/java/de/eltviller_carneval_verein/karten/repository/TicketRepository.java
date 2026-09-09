package de.eltviller_carneval_verein.karten.repository;

import java.util.List;

import de.eltviller_carneval_verein.karten.model.Event;

public interface TicketRepository {

	List<Event> loadEvents();

	void saveEvents(List<Event> events);

	void saveEvent(Event event);

	void deleteEvents(List<Event> events);

	void deleteEvent(Event event);
}
