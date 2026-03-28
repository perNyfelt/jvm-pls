package se.alipsa.jvmpls.core;

import se.alipsa.jvmpls.core.model.SymbolInfo;

import java.util.List;
import java.util.Optional;

/**
 * Lazy symbol source for external symbols such as dependency jars or the JDK.
 */
public interface SymbolProvider {
  Optional<SymbolInfo> findByFqn(String fqn);
  List<SymbolInfo> findBySimpleName(String simpleName);
  List<SymbolInfo> allInPackage(String pkgFqn);
}
