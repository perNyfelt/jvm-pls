package se.alipsa.jvmpls.server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Tracks canceled LSP request IDs so that long-running operations can check whether their request
 * has been canceled and stop early. Thread-safe for concurrent access from request handlers and the
 * cancellation notification handler.
 */
final class CancellationRegistry {
  private static final Logger LOG = Logger.getLogger(CancellationRegistry.class.getName());

  private final Set<String> canceledIds = ConcurrentHashMap.newKeySet();

  /** Mark a request ID as canceled (called from $/cancelRequest handler). */
  void cancel(String requestId) {
    if (requestId != null) {
      canceledIds.add(requestId);
      LOG.fine(() -> "Request canceled: " + requestId);
    }
  }

  /** Check if a request has been canceled. */
  boolean isCanceled(String requestId) {
    return requestId != null && canceledIds.contains(requestId);
  }

  /** Remove a request ID from tracking (called when the request completes). */
  void complete(String requestId) {
    if (requestId != null) {
      canceledIds.remove(requestId);
    }
  }

  /** Clear all tracked cancellations. */
  void clear() {
    canceledIds.clear();
  }
}
