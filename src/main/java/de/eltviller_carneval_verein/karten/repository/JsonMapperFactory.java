package de.eltviller_carneval_verein.karten.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Die einzige Stelle, an der der {@link ObjectMapper} für Events und Hallen konfiguriert wird. Die Repositorys
 * speichern damit, der {@code ChangeTracker} vergleicht damit: Nur wenn beide dieselbe Konfiguration benutzen,
 * entspricht "geändert" genau dem, was beim Speichern in der Datei landen würde.
 */
public final class JsonMapperFactory {

	private JsonMapperFactory() {
	}

	/** Erzeugt einen neuen Mapper (ObjectMapper ist nach der Konfiguration threadsicher und kann geteilt werden). */
	public static ObjectMapper create() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.enable(SerializationFeature.INDENT_OUTPUT);
		mapper.registerModule(new JavaTimeModule());
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		return mapper;
	}
}
