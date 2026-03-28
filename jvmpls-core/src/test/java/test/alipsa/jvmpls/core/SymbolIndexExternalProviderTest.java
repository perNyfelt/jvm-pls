package test.alipsa.jvmpls.core;

import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.core.SymbolIndex;
import se.alipsa.jvmpls.core.SymbolProvider;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.Range;
import se.alipsa.jvmpls.core.model.SymbolInfo;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolIndexExternalProviderTest {

  @Test
  void fallsBackToProviderAndCachesResults() {
    SymbolIndex index = new SymbolIndex();
    AtomicInteger fqnLookups = new AtomicInteger();
    index.registerProvider(new SymbolProvider() {
      @Override
      public Optional<SymbolInfo> findByFqn(String fqn) {
        fqnLookups.incrementAndGet();
        if ("java.util.List".equals(fqn)) {
          return Optional.of(symbol("java.util.List"));
        }
        return Optional.empty();
      }

      @Override
      public List<SymbolInfo> findBySimpleName(String simpleName) {
        return "List".equals(simpleName) ? List.of(symbol("java.util.List")) : List.of();
      }

      @Override
      public List<SymbolInfo> allInPackage(String pkgFqn) {
        return "java.util".equals(pkgFqn) ? List.of(symbol("java.util.List")) : List.of();
      }
    });

    assertTrue(index.findByFqn("java.util.List").isPresent());
    assertTrue(index.findByFqn("java.util.List").isPresent());
    assertEquals(1, fqnLookups.get(), "provider lookup should be cached");
  }

  @Test
  void sourceSymbolsOverrideExternalSymbolsInMergedQueries() {
    SymbolIndex index = new SymbolIndex();
    index.registerProvider(new SymbolProvider() {
      @Override
      public Optional<SymbolInfo> findByFqn(String fqn) {
        return Optional.of(symbol(fqn));
      }

      @Override
      public List<SymbolInfo> findBySimpleName(String simpleName) {
        return List.of(symbol("java.util.List"));
      }

      @Override
      public List<SymbolInfo> allInPackage(String pkgFqn) {
        return List.of(symbol("java.util.List"));
      }
    });

    SymbolInfo sourceList = new SymbolInfo(
        "java",
        SymbolInfo.Kind.CLASS,
        "java.util.List",
        "java.util",
        new Location("file:///workspace/List.java", new Range(new Position(0, 0), new Position(0, 1))),
        "",
        Set.of("public"),
        List.of());
    index.put("file:///workspace/List.java", sourceList);

    assertEquals("file:///workspace/List.java", index.findByFqn("java.util.List").orElseThrow().getLocation().getUri());
    assertEquals("file:///workspace/List.java",
        index.findBySimpleName("List").getFirst().getLocation().getUri());
    assertEquals("file:///workspace/List.java",
        index.allInPackage("java.util").getFirst().getLocation().getUri());
  }

  private static SymbolInfo symbol(String fqn) {
    return new SymbolInfo(
        "binary",
        SymbolInfo.Kind.CLASS,
        fqn,
        "java.util",
        new Location("jrt:/java.base/" + fqn.replace('.', '/') + ".class",
            new Range(new Position(0, 0), new Position(0, 1))),
        "",
        Set.of("public"),
        List.of());
  }
}
