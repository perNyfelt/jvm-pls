package se.alipsa.jvmpls.core;

import java.util.List;
import java.util.concurrent.Executor;

public interface PluginEnvironment {
  CoreQuery core(); // query global index

  Executor executor(); // background tasks if needed

  List<String> classpath(); // current workspace classpath (when available)

  void log(String level, String message, Throwable t);

  /** Returns the current cancellation token for cooperative cancellation checking. */
  default CancellationToken cancellationToken() {
    return CancellationToken.NONE;
  }
}
