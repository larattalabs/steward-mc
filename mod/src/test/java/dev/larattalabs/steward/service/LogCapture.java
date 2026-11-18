package dev.larattalabs.steward.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * (FENCE-TESTS) The "steward" logger's lines, for a test whose guard says nothing else it can read (a guard behind another one, where the only
 * difference is which of them stopped it). Opened on the logger at ALL, closed (appender removed, level put back) by {@link #close}.
 */
final class LogCapture extends AbstractAppender implements AutoCloseable {
	final List<String> lines = new CopyOnWriteArrayList<>();
	private final Logger logger;
	private final Level was;

	private LogCapture() {
		super("fence-tests-capture", null, null, true, Property.EMPTY_ARRAY);
		logger = (Logger) LogManager.getLogger("steward");
		was = logger.getLevel();
		start();
		logger.addAppender(this);
		logger.setLevel(Level.ALL);
	}

	static LogCapture open() {
		return new LogCapture();
	}

	@Override
	public void append(LogEvent event) {
		lines.add(event.getMessage().getFormattedMessage());
	}

	boolean has(String part) {
		return lines.stream().anyMatch(l -> l.contains(part));
	}

	@Override
	public void close() {
		logger.removeAppender(this);
		logger.setLevel(was);
		stop();
	}
}
