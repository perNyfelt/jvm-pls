package se.alipsa.jvmpls.core.server;

import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.PluginEnvironment;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Minimal PluginEnvironment used by the in-proc server bootstrap. */
final class DefaultPluginEnvironment implements PluginEnvironment {

  private final CoreQuery core;
  private final Executor executor;
  private final List<String> classpath;

  DefaultPluginEnvironment(CoreQuery core, Executor executor, List<String> classpath) {
    this.core = Objects.requireNonNull(core);
    this.executor = Objects.requireNonNull(executor);
    this.classpath = Objects.requireNonNull(classpath);
  }

  @Override public CoreQuery core() { return core; }

  @Override public Executor executor() { return executor; }

  @Override public List<String> classpath() { return classpath; }

  @Override public void log(String level, String message, Throwable t) {
    // simple stderr logger; replace as needed
    System.err.println("[" + level + "] " + message + (t != null ? " :: " + t : ""));
  }
}
