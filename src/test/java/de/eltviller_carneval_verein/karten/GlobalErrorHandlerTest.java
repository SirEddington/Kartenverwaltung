package de.eltviller_carneval_verein.karten;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.Test;

class GlobalErrorHandlerTest {

	@Test
	void describeUsesExceptionMessageWithCode() {
		assertEquals("SYS-001: Fehler beim Speichern der Datei: x.json",
				GlobalErrorHandler.describe(new RuntimeException("Fehler beim Speichern der Datei: x.json")));
	}

	@Test
	void describeFallsBackToClassNameWithoutMessage() {
		assertEquals("SYS-001: IllegalStateException", GlobalErrorHandler.describe(new IllegalStateException()));
		assertEquals("SYS-001: IllegalStateException", GlobalErrorHandler.describe(new IllegalStateException(" ")));
	}

	@Test
	void logLineContainsLevelLoggerMessageAndStackTrace() {
		LogRecord record = new LogRecord(Level.SEVERE, "SYS-001: kaputt");
		record.setLoggerName("test.logger");
		record.setThrown(new RuntimeException("Ursache"));

		String line = new AppLogging.LineFormatter().format(record);

		assertTrue(line.contains("SEVERE"));
		assertTrue(line.contains("test.logger: SYS-001: kaputt"));
		assertTrue(line.contains("java.lang.RuntimeException: Ursache"));
	}
}
