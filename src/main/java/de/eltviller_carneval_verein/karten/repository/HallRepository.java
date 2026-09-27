package de.eltviller_carneval_verein.karten.repository;

import java.util.List;

import de.eltviller_carneval_verein.karten.model.Hall;

public interface HallRepository {

	List<Hall> loadHalls();

	Hall findById(String hallId);

	void saveHalls(List<Hall> halls);

	void saveHall(Hall hall);

	void deleteHalls(List<Hall> halls);

	void deleteHall(Hall hall);
}
