package test.alipsa.jvmpls.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.DependencyGraph;

class DependencyGraphTest {

  @Test
  void replaceDependenciesAddsForwardAndReverseEdges() {
    DependencyGraph graph = new DependencyGraph();
    graph.replaceDependencies("A", List.of("B", "C"));

    assertEquals(Set.of("B", "C"), graph.dependsOn("A"));
    assertEquals(Set.of("A"), graph.directDependentsOf("B"));
    assertEquals(Set.of("A"), graph.directDependentsOf("C"));
  }

  @Test
  void replaceDependenciesRemovesStaleEdges() {
    DependencyGraph graph = new DependencyGraph();
    graph.replaceDependencies("A", List.of("B", "C"));
    graph.replaceDependencies("A", List.of("C", "D"));

    assertEquals(Set.of("C", "D"), graph.dependsOn("A"));
    assertEquals(Set.of(), graph.directDependentsOf("B"));
    assertEquals(Set.of("A"), graph.directDependentsOf("C"));
    assertEquals(Set.of("A"), graph.directDependentsOf("D"));
  }

  @Test
  void directDependentsReturnsEmptyForUnknownUri() {
    DependencyGraph graph = new DependencyGraph();
    assertEquals(Set.of(), graph.directDependentsOf("unknown"));
  }

  @Test
  void transitiveDependentsBfsTraversal() {
    DependencyGraph graph = new DependencyGraph();
    // A -> B -> C (A depends on B, B depends on C)
    graph.replaceDependencies("A", List.of("B"));
    graph.replaceDependencies("B", List.of("C"));

    // Transitive dependents of C: B (direct), A (transitive through B)
    Set<String> dependents = graph.transitiveDependentsOf("C");
    assertEquals(Set.of("A", "B"), dependents);
  }

  @Test
  void transitiveDependentsHandlesCycles() {
    DependencyGraph graph = new DependencyGraph();
    graph.replaceDependencies("A", List.of("B"));
    graph.replaceDependencies("B", List.of("A"));

    // Should not infinite loop
    Set<String> dependents = graph.transitiveDependentsOf("A");
    assertEquals(Set.of("B"), dependents);
  }

  @Test
  void removeFileCleansBothDirections() {
    DependencyGraph graph = new DependencyGraph();
    graph.replaceDependencies("A", List.of("B"));
    graph.replaceDependencies("C", List.of("B"));

    graph.removeFile("A");

    assertEquals(Set.of(), graph.dependsOn("A"));
    assertEquals(Set.of("C"), graph.directDependentsOf("B"));
  }

  @Test
  void clearRemovesAllEdges() {
    DependencyGraph graph = new DependencyGraph();
    graph.replaceDependencies("A", List.of("B"));
    graph.replaceDependencies("C", List.of("D"));

    graph.clear();

    assertEquals(Set.of(), graph.dependsOn("A"));
    assertEquals(Set.of(), graph.directDependentsOf("B"));
    assertEquals(Set.of(), graph.dependsOn("C"));
    assertEquals(Set.of(), graph.directDependentsOf("D"));
  }

  @Test
  void replaceDependenciesWithEmptyCollectionClearsEdges() {
    DependencyGraph graph = new DependencyGraph();
    graph.replaceDependencies("A", List.of("B", "C"));
    graph.replaceDependencies("A", List.of());

    assertEquals(Set.of(), graph.dependsOn("A"));
    assertEquals(Set.of(), graph.directDependentsOf("B"));
    assertEquals(Set.of(), graph.directDependentsOf("C"));
  }
}
