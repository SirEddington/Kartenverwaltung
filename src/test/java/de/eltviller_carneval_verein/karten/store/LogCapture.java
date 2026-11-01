package de.eltviller_carneval_verein.karten.store;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Schneidet die Logeinträge einer Klasse mit, auch {@code FINE}, bis {@link #close}. */
final class LogCapture extends Handler implements AutoCloseable {
	final List<LogRecord> records = new ArrayList<>();
	private final Logger logger;
	private final Level level;

	LogCapture(Class<?> source) {
		logger = Logger.getLogger(source.getName());
		level = logger.getLevel();
		logger.setLevel(Level.ALL);
		logger.addHandler(this);
	}

	@Override
	public synchronized void publish(LogRecord record) {
		records.add(record);
	}

	@Override
	public void flush() {
	}

	@Override
	public void close() {
		logger.removeHandler(this);
		logger.setLevel(level);
	}

	/** Wie viele Warnungen mit diesem Anfang kamen. */
	synchronized long warnings(String prefix) {
		return records.stream().filter(r -> r.getLevel() == Level.WARNING && r.getMessage().startsWith(prefix)).count();
	}
}
