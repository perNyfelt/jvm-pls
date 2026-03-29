package se.alipsa.jvmpls.core.server;

import java.util.List;

import se.alipsa.jvmpls.core.model.Diagnostic;

/** Sink for diagnostics (e.g., log, UI, test capture). */
@FunctionalInterface
public interface DiagnosticsPublisher {
  void publish(String uri, List<Diagnostic> diagnostics);

  DiagnosticsPublisher NO_OP = (uri, diags) -> {};
}
