package se.alipsa.jvmpls.core.server;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.PluginEnvironment;

/** Minimal PluginEnvironment used by the in-proc server bootstrap. */
final class DefaultPluginEnvironment implements PluginEnvironment {

  private static final Logger LOG = Logger.getLogger(DefaultPluginEnvironment.class.getName());

  private final CoreQuery core;
  private final Executor executor;
  private final List<String> classpath;

  DefaultPluginEnvironment(CoreQuery core, Executor executor, List<String> classpath) {
    this.core = Objects.requireNonNull(core);
    this.executor = Objects.requireNonNull(executor);
    this.classpath = Objects.requireNonNull(classpath);
  }

  @Override
  public CoreQuery core() {
    return core;
  }

  @Override
  public Executor executor() {
    return executor;
  }

  @Override
  public List<String> classpath() {
    return classpath;
  }

  @Override
  public void log(String level, String message, Throwable t) {
    Level julLevel =
        switch (level) {
          case "ERROR" -> Level.SEVERE;
          case "WARN" -> Level.WARNING;
          case "INFO" -> Level.FINE;
          default -> Level.FINE;
        };
    if (t == null) {
      LOG.log(julLevel, message);
    } else {
      LOG.log(julLevel, message, t);
    }
  }
}
