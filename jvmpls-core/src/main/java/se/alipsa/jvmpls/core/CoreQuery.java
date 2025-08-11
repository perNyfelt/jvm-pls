package se.alipsa.jvmpls.core;

import se.alipsa.jvmpls.core.model.SymbolInfo;

import java.util.List;
import java.util.Optional;

public interface CoreQuery {
  Optional<SymbolInfo> findByFqn(String fqn);
  List<SymbolInfo> allInPackage(String pkgFqn);
}
