package se.alipsa.jvmpls.core.server;

import se.alipsa.jvmpls.core.model.Diagnostic;

import java.util.List;

/** Sink for diagnostics (e.g., log, UI, test capture). */
@FunctionalInterface
public interface DiagnosticsPublisher {
  void publish(String uri, List<Diagnostic> diagnostics);

  DiagnosticsPublisher NO_OP = (uri, diags) -> {};
}
