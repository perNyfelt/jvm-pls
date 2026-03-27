package se.alipsa.jvmpls.core;

import se.alipsa.jvmpls.core.model.SymbolInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SymbolIndex implements CoreQuery {

  private final Map<String, SymbolInfo> byFqn = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> fileToDecls = new ConcurrentHashMap<>();

  public void put(String fileUri, SymbolInfo sym) {
    byFqn.put(sym.getFqName(), sym);
    fileToDecls.computeIfAbsent(fileUri, k -> ConcurrentHashMap.newKeySet()).add(sym.getFqName());
  }

  public void removeFile(String fileUri) {
    Set<String> decls = fileToDecls.remove(fileUri);
    if (decls != null) decls.forEach(byFqn::remove);
  }

  @Override public Optional<SymbolInfo> findByFqn(String fqn) { return Optional.ofNullable(byFqn.get(fqn)); }

  @Override public List<SymbolInfo> allInPackage(String pkg) {
    String prefix = pkg.endsWith(".") ? pkg : (pkg + ".");
    ArrayList<SymbolInfo> out = new ArrayList<>();
    byFqn.forEach((k,v) -> { if (k.startsWith(prefix)) out.add(v); });
    return out;
  }

  @Override
  public List<SymbolInfo> findBySimpleName(String simpleName) {
    List<SymbolInfo> results = new ArrayList<>();
    if (simpleName == null || simpleName.isEmpty()) {
      return results;
    }
    for (SymbolInfo sym : byFqn.values()) {
      String fqn = sym.getFqName();
      int lastDot = fqn.lastIndexOf('.');
      int lastHash = fqn.lastIndexOf('#');
      int lastSep = Math.max(lastDot, lastHash);
      String name = fqn.substring(lastSep + 1);
      // for methods, fqn contains signature, so strip it
      int openParen = name.indexOf('(');
      if (openParen > 0) {
        name = name.substring(0, openParen);
      }
      if (name.equals(simpleName)) {
        results.add(sym);
      }
    }
    return results;
  }
}
