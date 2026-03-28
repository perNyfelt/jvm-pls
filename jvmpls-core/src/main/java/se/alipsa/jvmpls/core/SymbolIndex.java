package se.alipsa.jvmpls.core;

import se.alipsa.jvmpls.core.model.SymbolInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SymbolIndex implements CoreQuery {

  private final Map<String, SymbolInfo> byFqn = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> fileToDecls = new ConcurrentHashMap<>();
  private final List<SymbolProvider> providers = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, Optional<SymbolInfo>> providerByFqnCache = new ConcurrentHashMap<>();
  private final Map<String, List<SymbolInfo>> providerBySimpleNameCache = new ConcurrentHashMap<>();
  private final Map<String, List<SymbolInfo>> providerByPackageCache = new ConcurrentHashMap<>();

  public void put(String fileUri, SymbolInfo sym) {
    byFqn.put(sym.getFqName(), sym);
    fileToDecls.computeIfAbsent(fileUri, k -> ConcurrentHashMap.newKeySet()).add(sym.getFqName());
  }

  public void registerProvider(SymbolProvider provider) {
    Objects.requireNonNull(provider, "provider");
    providers.add(provider);
    providerByFqnCache.clear();
    providerBySimpleNameCache.clear();
    providerByPackageCache.clear();
  }

  public void removeFile(String fileUri) {
    Set<String> decls = fileToDecls.remove(fileUri);
    if (decls != null) decls.forEach(byFqn::remove);
  }

  @Override
  public Optional<SymbolInfo> findByFqn(String fqn) {
    SymbolInfo local = byFqn.get(fqn);
    if (local != null) {
      return Optional.of(local);
    }
    return providerByFqnCache.computeIfAbsent(fqn, this::resolveExternalByFqn);
  }

  @Override public List<SymbolInfo> allInPackage(String pkg) {
    String prefix = pkg.endsWith(".") ? pkg : (pkg + ".");
    Map<String, SymbolInfo> out = new LinkedHashMap<>();
    for (SymbolInfo external : providerByPackageCache.computeIfAbsent(pkg, this::resolveExternalByPackage)) {
      out.put(external.getFqName(), external);
    }
    byFqn.forEach((k, v) -> {
      if (k.startsWith(prefix)) {
        out.put(v.getFqName(), v);
      }
    });
    return List.copyOf(out.values());
  }

  @Override
  public List<SymbolInfo> findBySimpleName(String simpleName) {
    if (simpleName == null || simpleName.isEmpty()) {
      return List.of();
    }
    Map<String, SymbolInfo> results = new LinkedHashMap<>();
    for (SymbolInfo external : providerBySimpleNameCache.computeIfAbsent(simpleName, this::resolveExternalBySimpleName)) {
      results.put(external.getFqName(), external);
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
        results.put(sym.getFqName(), sym);
      }
    }
    return List.copyOf(results.values());
  }

  private Optional<SymbolInfo> resolveExternalByFqn(String fqn) {
    synchronized (providers) {
      for (SymbolProvider provider : providers) {
        Optional<SymbolInfo> hit = provider.findByFqn(fqn);
        if (hit.isPresent()) {
          return hit;
        }
      }
    }
    return Optional.empty();
  }

  private List<SymbolInfo> resolveExternalBySimpleName(String simpleName) {
    Map<String, SymbolInfo> results = new LinkedHashMap<>();
    synchronized (providers) {
      for (SymbolProvider provider : providers) {
        for (SymbolInfo symbol : provider.findBySimpleName(simpleName)) {
          results.putIfAbsent(symbol.getFqName(), symbol);
        }
      }
    }
    return List.copyOf(results.values());
  }

  private List<SymbolInfo> resolveExternalByPackage(String pkg) {
    Map<String, SymbolInfo> results = new LinkedHashMap<>();
    synchronized (providers) {
      for (SymbolProvider provider : providers) {
        for (SymbolInfo symbol : provider.allInPackage(pkg)) {
          results.putIfAbsent(symbol.getFqName(), symbol);
        }
      }
    }
    return List.copyOf(results.values());
  }
}
