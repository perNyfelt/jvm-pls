package se.alipsa.jvmpls.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-level dependency graph (very lightweight).
 * fromUri -> set of to URIs or FQNs (you decide what to store).
 * For MVP we only track "fromUri -> toFqn" edges; you can evolve this later.
 */
public final class DependencyGraph {

  private final Map<String, Set<String>> edges = new ConcurrentHashMap<>();

  public void addEdge(String fromUri, String toFqnOrUri) {
    edges.computeIfAbsent(fromUri, k -> ConcurrentHashMap.newKeySet()).add(toFqnOrUri);
  }

  /** Return a snapshot of the dependents we know for this file. */
  public Set<String> dependsOn(String fromUri) {
    return edges.getOrDefault(fromUri, Set.of());
  }

  public void removeFile(String uri) {
    edges.remove(uri);
    // Optionally remove reverse edges if you store them
  }

  public void clear() {
    edges.clear();
  }
}
