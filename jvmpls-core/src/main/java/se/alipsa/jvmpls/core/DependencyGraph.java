package se.alipsa.jvmpls.core;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * File-level dependency graph storing normalized URI-to-URI edges. Maintains both forward edges
 * (file A depends on file B) and reverse edges (file B is depended on by file A) for cheap
 * invalidation queries.
 *
 * <p>All public methods are synchronized to guarantee consistent reads across forward and reverse
 * maps.
 */
public final class DependencyGraph {

  /** Forward edges: fromUri -> set of toUris (what does this file depend on?) */
  private final Map<String, Set<String>> forward = new HashMap<>();

  /** Reverse edges: toUri -> set of fromUris (who depends on this file?) */
  private final Map<String, Set<String>> reverse = new HashMap<>();

  /**
   * Replace all dependencies for a file atomically. Called during reindex to set the complete
   * dependency set for a file, removing stale edges and adding new ones.
   *
   * @param fromUri the file whose dependencies are being replaced
   * @param toUris the complete set of URIs that fromUri now depends on
   */
  public synchronized void replaceDependencies(String fromUri, Collection<String> toUris) {
    // Remove old forward edges and their corresponding reverse entries
    Set<String> oldDeps = forward.remove(fromUri);
    if (oldDeps != null) {
      for (String oldDep : oldDeps) {
        Set<String> revSet = reverse.get(oldDep);
        if (revSet != null) {
          revSet.remove(fromUri);
          if (revSet.isEmpty()) {
            reverse.remove(oldDep);
          }
        }
      }
    }

    // Add new forward edges and corresponding reverse entries
    if (toUris != null && !toUris.isEmpty()) {
      Set<String> newDeps = new HashSet<>(toUris);
      forward.put(fromUri, newDeps);
      for (String toUri : toUris) {
        reverse.computeIfAbsent(toUri, ignored -> new HashSet<>()).add(fromUri);
      }
    }
  }

  /**
   * Get files that directly depend on the given URI.
   *
   * @param uri the target URI
   * @return unmodifiable snapshot of direct dependents
   */
  public synchronized Set<String> directDependentsOf(String uri) {
    Set<String> deps = reverse.get(uri);
    if (deps == null || deps.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(deps);
  }

  /**
   * Get all files that transitively depend on the given URI, using BFS with cycle detection.
   *
   * @param uri the target URI
   * @return unmodifiable set of all transitive dependents (does not include uri itself)
   */
  public synchronized Set<String> transitiveDependentsOf(String uri) {
    Set<String> result = new HashSet<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    queue.add(uri);
    Set<String> visited = new HashSet<>();
    visited.add(uri);

    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      Set<String> dependents = reverse.get(current);
      if (dependents != null) {
        for (String dep : dependents) {
          if (visited.add(dep)) {
            result.add(dep);
            queue.add(dep);
          }
        }
      }
    }
    return Set.copyOf(result);
  }

  /**
   * Return a snapshot of the URIs that this file depends on.
   *
   * @param fromUri the source file URI
   * @return unmodifiable snapshot of dependencies, or empty set if none
   */
  public synchronized Set<String> dependsOn(String fromUri) {
    Set<String> deps = forward.get(fromUri);
    if (deps == null || deps.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(deps);
  }

  /**
   * Remove a file and all its edges (both forward and reverse).
   *
   * @param uri the file URI to remove
   */
  public synchronized void removeFile(String uri) {
    // Remove forward edges: uri depends on X -> clean reverse entries for each X
    Set<String> deps = forward.remove(uri);
    if (deps != null) {
      for (String dep : deps) {
        Set<String> revSet = reverse.get(dep);
        if (revSet != null) {
          revSet.remove(uri);
          if (revSet.isEmpty()) {
            reverse.remove(dep);
          }
        }
      }
    }

    // Remove reverse edges: Y depends on uri -> clean forward entries for each Y
    Set<String> dependents = reverse.remove(uri);
    if (dependents != null) {
      for (String dependent : dependents) {
        Set<String> fwdSet = forward.get(dependent);
        if (fwdSet != null) {
          fwdSet.remove(uri);
          if (fwdSet.isEmpty()) {
            forward.remove(dependent);
          }
        }
      }
    }
  }

  /** Clear all edges. */
  public synchronized void clear() {
    forward.clear();
    reverse.clear();
  }
}
