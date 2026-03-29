package se.alipsa.jvmpls.core;

import se.alipsa.jvmpls.core.model.SymbolInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SymbolIndex implements CoreQuery {
  private static final Logger LOG = Logger.getLogger(SymbolIndex.class.getName());

  private final Map<String, SymbolInfo> byFqn = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> fileToDecls = new ConcurrentHashMap<>();
  private final List<SymbolProvider> providers = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, Optional<SymbolInfo>> providerByFqnCache = new ConcurrentHashMap<>();
  private final Map<String, List<SymbolInfo>> providerBySimpleNameCache = new ConcurrentHashMap<>();
  private final Map<String, List<SymbolInfo>> providerByPackageCache = new ConcurrentHashMap<>();
  private final Map<String, List<SymbolInfo>> providerByOwnerCache = new ConcurrentHashMap<>();

  public void put(String fileUri, SymbolInfo sym) {
    byFqn.put(sym.getFqName(), sym);
    fileToDecls.computeIfAbsent(fileUri, k -> ConcurrentHashMap.newKeySet()).add(sym.getFqName());
    providerByOwnerCache.remove(sym.getContainerFqName());
  }

  public void registerProvider(SymbolProvider provider) {
    Objects.requireNonNull(provider, "provider");
    providers.add(provider);
    providerByFqnCache.clear();
    providerBySimpleNameCache.clear();
    providerByPackageCache.clear();
    providerByOwnerCache.clear();
  }

  public void removeFile(String fileUri) {
    Set<String> decls = fileToDecls.remove(fileUri);
    if (decls != null) {
      Set<String> affectedOwners = new LinkedHashSet<>();
      for (String decl : decls) {
        SymbolInfo removed = byFqn.remove(decl);
        if (removed != null) {
          affectedOwners.add(removed.getContainerFqName());
        }
      }
      affectedOwners.forEach(providerByOwnerCache::remove);
    }
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
      String name = simpleNameOf(sym);
      if (name.equals(simpleName)) {
        results.put(sym.getFqName(), sym);
      }
    }
    return List.copyOf(results.values());
  }

  @Override
  public List<SymbolInfo> membersOf(String ownerFqn) {
    if (ownerFqn == null || ownerFqn.isBlank()) {
      return List.of();
    }
    Map<String, SymbolInfo> results = new LinkedHashMap<>();
    for (SymbolInfo external : providerByOwnerCache.computeIfAbsent(ownerFqn, this::resolveExternalMembers)) {
      results.put(external.getFqName(), external);
    }
    for (SymbolInfo symbol : byFqn.values()) {
      if (ownerFqn.equals(symbol.getContainerFqName())) {
        results.put(symbol.getFqName(), symbol);
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

  private List<SymbolInfo> resolveExternalMembers(String ownerFqn) {
    Map<String, SymbolInfo> results = new LinkedHashMap<>();
    synchronized (providers) {
      for (SymbolProvider provider : providers) {
        try {
          for (SymbolInfo symbol : provider.membersOf(ownerFqn)) {
            results.putIfAbsent(symbol.getFqName(), symbol);
          }
        } catch (RuntimeException e) {
          LOG.log(Level.WARNING, "Symbol provider failed while resolving members for " + ownerFqn, e);
        }
      }
    }
    return List.copyOf(results.values());
  }

  private static String simpleNameOf(SymbolInfo symbol) {
    String fqn = symbol.getFqName();
    int lastDot = fqn.lastIndexOf('.');
    int lastHash = fqn.lastIndexOf('#');
    int lastSep = Math.max(lastDot, lastHash);
    String name = fqn.substring(lastSep + 1);
    int openParen = name.indexOf('(');
    if (openParen > 0) {
      name = name.substring(0, openParen);
    }
    return name;
  }
}
