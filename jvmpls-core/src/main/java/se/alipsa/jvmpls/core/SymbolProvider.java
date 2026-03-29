package se.alipsa.jvmpls.core;

import java.util.List;
import java.util.Optional;

import se.alipsa.jvmpls.core.model.SymbolInfo;

/** Lazy symbol source for external symbols such as dependency jars or the JDK. */
public interface SymbolProvider {
  Optional<SymbolInfo> findByFqn(String fqn);

  List<SymbolInfo> findBySimpleName(String simpleName);

  List<SymbolInfo> allInPackage(String pkgFqn);

  default List<SymbolInfo> membersOf(String ownerFqn) {
    return List.of();
  }

  default List<SymbolInfo> constructorsOf(String ownerFqn) {
    return List.of();
  }

  default List<String> supertypesOf(String typeFqn) {
    return List.of();
  }
}
