package de.eltviller_carneval_verein.karten;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Richtet die Log-Datei der Anwendung ein: {@code <AppData>/logs/kartenverwaltung-N.log},
 * rotierend (5 Dateien à max. 1 MB). Alle {@link Logger} der Anwendung schreiben dorthin
 * (und weiterhin auf die Konsole).
 */
public final class AppLogging {

	private static final int MAX_BYTES_PER_FILE = 1_000_000;
	private static final int FILE_COUNT = 5;
	private static boolean initialized;

	private AppLogging() {
	}

	/** Verzeichnis, in dem die Log-Dateien liegen. */
	public static File logDir() {
		return new File(AppPaths.appDataDir(), "logs");
	}

	/** Richtet die Log-Datei ein; mehrfaches Aufrufen ist harmlos. Ein Fehler hier verhindert nie den Start. */
	public static synchronized void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		try {
			Files.createDirectories(logDir().toPath());
			FileHandler handler = new FileHandler(new File(logDir(), "kartenverwaltung-%g.log").getPath(), MAX_BYTES_PER_FILE, FILE_COUNT, true);
			handler.setEncoding("UTF-8");
			handler.setFormatter(new LineFormatter());
			Logger.getLogger("").addHandler(handler);
		} catch (IOException | SecurityException e) {
			System.err.println("Log-Datei konnte nicht angelegt werden: " + e.getMessage());
		}
	}

	/** Eine Zeile pro Eintrag ("2026-10-06 18:02:11 SEVERE  logger: Text"), gefolgt vom Stacktrace. */
	static final class LineFormatter extends Formatter {
		private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

		@Override
		public String format(LogRecord logRecord) {
			StringBuilder line = new StringBuilder();
			line.append(LocalDateTime.now().format(TIME)).append(' ');
			line.append(String.format("%-7s", logRecord.getLevel().getName())).append(' ');
			line.append(logRecord.getLoggerName()).append(": ").append(formatMessage(logRecord)).append(System.lineSeparator());
			if (logRecord.getThrown() != null) {
				StringWriter trace = new StringWriter();
				logRecord.getThrown().printStackTrace(new PrintWriter(trace));
				line.append(trace);
			}
			return line.toString();
		}
	}
}
