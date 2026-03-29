package test.alipsa.jvmpls.server;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

final class TestLogCapture implements AutoCloseable {

  private final Logger logger;
  private final Handler handler;
  private final boolean useParentHandlers;
  private final Level level;
  private final List<LogRecord> records = new ArrayList<>();

  private TestLogCapture(Logger logger) {
    this.logger = logger;
    this.useParentHandlers = logger.getUseParentHandlers();
    this.level = logger.getLevel();
    this.handler = new Handler() {
      @Override
      public void publish(LogRecord record) {
        records.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    this.handler.setLevel(Level.ALL);
    logger.setUseParentHandlers(false);
    logger.setLevel(Level.ALL);
    logger.addHandler(handler);
  }

  static TestLogCapture capture(Class<?> owner) {
    return new TestLogCapture(Logger.getLogger(owner.getName()));
  }

  static TestLogCapture capture(String loggerName) {
    return new TestLogCapture(Logger.getLogger(loggerName));
  }

  boolean contains(Level level, String text) {
    return records.stream().anyMatch(record ->
        record.getLevel().equals(level)
            && record.getMessage() != null
            && record.getMessage().contains(text));
  }

  @Override
  public void close() {
    logger.removeHandler(handler);
    logger.setUseParentHandlers(useParentHandlers);
    logger.setLevel(level);
  }
}
