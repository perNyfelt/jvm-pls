package se.alipsa.jvmpls.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.SymbolInfo;

public final class SymbolIndex implements CoreQuery {
  private static final Logger LOG = Logger.getLogger(SymbolIndex.class.getName());
  private static final char REF_KEY_SEPARATOR = '\0';

  private final Map<String, SymbolInfo> byFqn = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> fileToDecls = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> fileToRefs = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Location>> refsByTargetFqn = new ConcurrentHashMap<>();
  private final Map<String, List<String>> directSupertypesByType = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> directSubtypesBySupertype = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> fileToCalls = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Map<String, Location>>> outgoingCallsByCaller =
      new ConcurrentHashMap<>();
  private final Map<String, Map<String, Map<String, Location>>> incomingCallsByCallee =
      new ConcurrentHashMap<>();
  private final List<SymbolProvider> providers = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, Optional<SymbolInfo>> providerByFqnCache = new ConcurrentHashMap<>();
  private final Map<String, List<SymbolInfo>> providerBySimpleNameCache = new ConcurrentHashMap<>();
  private final Map<String, List<SymbolInfo>> providerByPackageCache = new ConcurrentHashMap<>();
  private final Map<String, List<SymbolInfo>> providerByOwnerCache = new ConcurrentHashMap<>();
  private final Map<String, List<SymbolInfo>> providerByConstructorCache =
      new ConcurrentHashMap<>();
  private final Map<String, List<String>> providerByTypeHierarchyCache = new ConcurrentHashMap<>();
  private final Map<String, List<SymbolInfo>> providerSearchCache = new ConcurrentHashMap<>();

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
    providerByConstructorCache.clear();
    providerByTypeHierarchyCache.clear();
    providerSearchCache.clear();
  }

  public void removeFile(String fileUri) {
    Set<String> decls = fileToDecls.remove(fileUri);
    if (decls != null) {
      Set<String> affectedOwners = new LinkedHashSet<>();
      for (String decl : decls) {
        SymbolInfo removed = byFqn.remove(decl);
        if (removed != null) {
          affectedOwners.add(removed.getContainerFqName());
          removeTypeHierarchy(removed.getFqName());
        }
      }
      affectedOwners.forEach(providerByOwnerCache::remove);
      affectedOwners.forEach(providerByConstructorCache::remove);
    }
    Set<String> refs = fileToRefs.remove(fileUri);
    if (refs != null) {
      for (String ref : refs) {
        int separator = ref.indexOf(REF_KEY_SEPARATOR);
        if (separator < 0) {
          continue;
        }
        String targetFqn = ref.substring(0, separator);
        String key = ref.substring(separator + 1);
        Map<String, Location> locations = refsByTargetFqn.get(targetFqn);
        if (locations != null) {
          locations.remove(key);
          if (locations.isEmpty()) {
            refsByTargetFqn.remove(targetFqn);
          }
        }
      }
    }
    Set<String> calls = fileToCalls.remove(fileUri);
    if (calls != null) {
      for (String call : calls) {
        int callerSeparator = call.indexOf(REF_KEY_SEPARATOR);
        int calleeSeparator =
            callerSeparator < 0 ? -1 : call.indexOf(REF_KEY_SEPARATOR, callerSeparator + 1);
        if (callerSeparator < 0 || calleeSeparator < 0) {
          continue;
        }
        String callerFqn = call.substring(0, callerSeparator);
        String calleeFqn = call.substring(callerSeparator + 1, calleeSeparator);
        String key = call.substring(calleeSeparator + 1);
        Map<String, Map<String, Location>> outgoing = outgoingCallsByCaller.get(callerFqn);
        if (outgoing != null) {
          removeCallEntry(outgoing, calleeFqn, key);
          if (outgoing.isEmpty()) {
            outgoingCallsByCaller.remove(callerFqn);
          }
        }
        Map<String, Map<String, Location>> incoming = incomingCallsByCallee.get(calleeFqn);
        if (incoming != null) {
          removeCallEntry(incoming, callerFqn, key);
          if (incoming.isEmpty()) {
            incomingCallsByCallee.remove(calleeFqn);
          }
        }
      }
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

  @Override
  public List<SymbolInfo> allInPackage(String pkg) {
    String prefix = pkg.endsWith(".") ? pkg : (pkg + ".");
    Map<String, SymbolInfo> out = new LinkedHashMap<>();
    for (SymbolInfo external :
        providerByPackageCache.computeIfAbsent(pkg, this::resolveExternalByPackage)) {
      out.put(external.getFqName(), external);
    }
    byFqn.forEach(
        (k, v) -> {
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
    for (SymbolInfo external :
        providerBySimpleNameCache.computeIfAbsent(simpleName, this::resolveExternalBySimpleName)) {
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
    for (SymbolInfo external :
        providerByOwnerCache.computeIfAbsent(ownerFqn, this::resolveExternalMembers)) {
      results.put(external.getFqName(), external);
    }
    for (SymbolInfo symbol : byFqn.values()) {
      if (ownerFqn.equals(symbol.getContainerFqName())) {
        results.put(symbol.getFqName(), symbol);
      }
    }
    return List.copyOf(results.values());
  }

  @Override
  public List<SymbolInfo> constructorsOf(String ownerFqn) {
    if (ownerFqn == null || ownerFqn.isBlank()) {
      return List.of();
    }
    Map<String, SymbolInfo> results = new LinkedHashMap<>();
    for (SymbolInfo external :
        providerByConstructorCache.computeIfAbsent(ownerFqn, this::resolveExternalConstructors)) {
      results.put(external.getFqName(), external);
    }
    for (SymbolInfo symbol : byFqn.values()) {
      if (ownerFqn.equals(symbol.getContainerFqName())
          && symbol.getKind() == SymbolInfo.Kind.CONSTRUCTOR) {
        results.put(symbol.getFqName(), symbol);
      }
    }
    return List.copyOf(results.values());
  }

  @Override
  public List<String> supertypesOf(String typeFqn) {
    if (typeFqn == null || typeFqn.isBlank()) {
      return List.of();
    }
    return providerByTypeHierarchyCache.computeIfAbsent(typeFqn, this::resolveExternalSupertypes);
  }

  public void reportReference(String fileUri, String targetFqn, Location useSite) {
    if (fileUri == null
        || fileUri.isBlank()
        || targetFqn == null
        || targetFqn.isBlank()
        || useSite == null
        || useSite.getRange() == null
        || useSite.getRange().start == null
        || useSite.getRange().end == null) {
      return;
    }
    String key = locationKey(useSite);
    refsByTargetFqn
        .computeIfAbsent(targetFqn, ignored -> new ConcurrentHashMap<>())
        .put(key, useSite);
    fileToRefs
        .computeIfAbsent(fileUri, ignored -> ConcurrentHashMap.newKeySet())
        .add(targetFqn + REF_KEY_SEPARATOR + key);
  }

  public List<Location> referencesTo(String targetFqn) {
    Map<String, Location> references = refsByTargetFqn.get(targetFqn);
    if (references == null || references.isEmpty()) {
      return List.of();
    }
    return references.values().stream().sorted(locationOrder()).toList();
  }

  public void reportDirectSupertypes(String typeFqn, List<String> directSupertypes) {
    if (typeFqn == null || typeFqn.isBlank()) {
      return;
    }
    removeTypeHierarchy(typeFqn);
    if (directSupertypes == null || directSupertypes.isEmpty()) {
      return;
    }
    List<String> normalized =
        directSupertypes.stream()
            .filter(Objects::nonNull)
            .filter(s -> !s.isBlank())
            .distinct()
            .toList();
    if (normalized.isEmpty()) {
      return;
    }
    directSupertypesByType.put(typeFqn, normalized);
    for (String supertype : normalized) {
      directSubtypesBySupertype
          .computeIfAbsent(supertype, ignored -> ConcurrentHashMap.newKeySet())
          .add(typeFqn);
    }
  }

  public List<String> directSupertypesOfLocal(String typeFqn) {
    List<String> local = directSupertypesByType.get(typeFqn);
    return local == null ? List.of() : local;
  }

  public List<SymbolInfo> directSubtypesOf(String supertypeFqn) {
    Set<String> subtypes = directSubtypesBySupertype.get(supertypeFqn);
    if (subtypes == null || subtypes.isEmpty()) {
      return List.of();
    }
    return subtypes.stream()
        .map(this::findByFqn)
        .flatMap(Optional::stream)
        .sorted(SymbolPresentation.locationOrder())
        .toList();
  }

  public void reportCall(String fileUri, String callerFqn, String calleeFqn, Location useSite) {
    if (fileUri == null
        || fileUri.isBlank()
        || callerFqn == null
        || callerFqn.isBlank()
        || calleeFqn == null
        || calleeFqn.isBlank()
        || useSite == null
        || useSite.getRange() == null) {
      return;
    }
    String key = locationKey(useSite);
    outgoingCallsByCaller
        .computeIfAbsent(callerFqn, ignored -> new ConcurrentHashMap<>())
        .computeIfAbsent(calleeFqn, ignored -> new ConcurrentHashMap<>())
        .put(key, useSite);
    incomingCallsByCallee
        .computeIfAbsent(calleeFqn, ignored -> new ConcurrentHashMap<>())
        .computeIfAbsent(callerFqn, ignored -> new ConcurrentHashMap<>())
        .put(key, useSite);
    fileToCalls
        .computeIfAbsent(fileUri, ignored -> ConcurrentHashMap.newKeySet())
        .add(callerFqn + REF_KEY_SEPARATOR + calleeFqn + REF_KEY_SEPARATOR + key);
  }

  public Map<String, List<Location>> outgoingCallsFrom(String callerFqn) {
    return callMap(outgoingCallsByCaller.get(callerFqn));
  }

  public Map<String, List<Location>> incomingCallsTo(String calleeFqn) {
    return callMap(incomingCallsByCallee.get(calleeFqn));
  }

  public List<SymbolInfo> declarationsInFile(String fileUri) {
    Set<String> decls = fileToDecls.get(fileUri);
    if (decls == null || decls.isEmpty()) {
      return List.of();
    }
    return decls.stream()
        .map(byFqn::get)
        .filter(Objects::nonNull)
        .sorted(SymbolPresentation.locationOrder())
        .toList();
  }

  public List<SymbolInfo> search(String query, int limit) {
    if (query == null || query.isBlank() || limit <= 0) {
      return List.of();
    }
    Map<String, SymbolInfo> merged = new LinkedHashMap<>();
    for (SymbolInfo symbol :
        providerSearchCache.computeIfAbsent(query, q -> resolveExternalSearch(q, limit))) {
      merged.put(symbol.getFqName(), symbol);
    }
    for (SymbolInfo symbol : byFqn.values()) {
      if (matchesWorkspaceQuery(symbol, query)) {
        merged.put(symbol.getFqName(), symbol);
      }
    }
    return merged.values().stream()
        .filter(symbol -> symbol.getKind() != SymbolInfo.Kind.PACKAGE)
        .sorted(
            Comparator.comparingInt((SymbolInfo symbol) -> workspaceScore(symbol, query))
                .reversed()
                .thenComparing(SymbolPresentation.locationOrder()))
        .limit(limit)
        .toList();
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
          LOG.log(
              Level.WARNING, "Symbol provider failed while resolving members for " + ownerFqn, e);
        }
      }
    }
    return List.copyOf(results.values());
  }

  private List<String> resolveExternalSupertypes(String typeFqn) {
    LinkedHashSet<String> results = new LinkedHashSet<>();
    synchronized (providers) {
      for (SymbolProvider provider : providers) {
        try {
          results.addAll(provider.supertypesOf(typeFqn));
        } catch (RuntimeException e) {
          LOG.log(
              Level.WARNING, "Symbol provider failed while resolving supertypes for " + typeFqn, e);
        }
      }
    }
    return List.copyOf(results);
  }

  private List<SymbolInfo> resolveExternalConstructors(String ownerFqn) {
    Map<String, SymbolInfo> results = new LinkedHashMap<>();
    synchronized (providers) {
      for (SymbolProvider provider : providers) {
        try {
          for (SymbolInfo symbol : provider.constructorsOf(ownerFqn)) {
            results.putIfAbsent(symbol.getFqName(), symbol);
          }
        } catch (RuntimeException e) {
          LOG.log(
              Level.WARNING,
              "Symbol provider failed while resolving constructors for " + ownerFqn,
              e);
        }
      }
    }
    return List.copyOf(results.values());
  }

  private List<SymbolInfo> resolveExternalSearch(String query, int limit) {
    Map<String, SymbolInfo> results = new LinkedHashMap<>();
    synchronized (providers) {
      for (SymbolProvider provider : providers) {
        try {
          for (SymbolInfo symbol : provider.search(query, limit)) {
            results.putIfAbsent(symbol.getFqName(), symbol);
          }
        } catch (RuntimeException e) {
          LOG.log(Level.WARNING, "Symbol provider failed while searching for " + query, e);
        }
      }
    }
    return List.copyOf(results.values());
  }

  private static String simpleNameOf(SymbolInfo symbol) {
    return SymbolPresentation.simpleName(symbol);
  }

  List<SymbolInfo> allSymbols() {
    return byFqn.values().stream().sorted(SymbolPresentation.locationOrder()).toList();
  }

  private static int workspaceScore(SymbolInfo symbol, String query) {
    String normalized = query.toLowerCase(Locale.ROOT);
    String simpleName = simpleNameOf(symbol).toLowerCase(Locale.ROOT);
    String fqn = symbol.getFqName().toLowerCase(Locale.ROOT);
    int score = 0;
    if (simpleName.equals(normalized)) {
      score += 1000;
    } else if (simpleName.startsWith(normalized)) {
      score += 800;
    } else if (simpleName.contains(normalized)) {
      score += 650;
    } else if (fqn.startsWith(normalized)) {
      score += 600;
    } else if (fqn.contains(normalized)) {
      score += 450;
    }
    if ("binary".equals(symbol.getLanguageId())) {
      score -= 100;
    }
    if (symbol.getSyntheticOrigin() != null
        && symbol.getSyntheticOrigin() != se.alipsa.jvmpls.core.model.SyntheticOrigin.NONE) {
      score -= 50;
    }
    return score;
  }

  private static boolean matchesWorkspaceQuery(SymbolInfo symbol, String query) {
    return workspaceScore(symbol, query) > 0;
  }

  private void removeTypeHierarchy(String typeFqn) {
    List<String> supertypes = directSupertypesByType.remove(typeFqn);
    if (supertypes == null) {
      return;
    }
    for (String supertype : supertypes) {
      Set<String> subtypes = directSubtypesBySupertype.get(supertype);
      if (subtypes != null) {
        subtypes.remove(typeFqn);
        if (subtypes.isEmpty()) {
          directSubtypesBySupertype.remove(supertype);
        }
      }
    }
  }

  private static void removeCallEntry(
      Map<String, Map<String, Location>> edges, String targetFqn, String key) {
    Map<String, Location> locations = edges.get(targetFqn);
    if (locations == null) {
      return;
    }
    locations.remove(key);
    if (locations.isEmpty()) {
      edges.remove(targetFqn);
    }
  }

  private static Map<String, List<Location>> callMap(Map<String, Map<String, Location>> calls) {
    if (calls == null || calls.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<String, List<Location>> result = new LinkedHashMap<>();
    calls.forEach(
        (symbolFqn, locations) ->
            result.put(symbolFqn, locations.values().stream().sorted(locationOrder()).toList()));
    return Map.copyOf(result);
  }

  private static String locationKey(Location location) {
    return location.getUri()
        + ':'
        + location.getRange().start.line
        + ':'
        + location.getRange().start.column
        + '-'
        + location.getRange().end.line
        + ':'
        + location.getRange().end.column;
  }

  private static Comparator<Location> locationOrder() {
    return Comparator.comparing(Location::getUri)
        .thenComparingInt(location -> location.getRange().start.line)
        .thenComparingInt(location -> location.getRange().start.column)
        .thenComparingInt(location -> location.getRange().end.line)
        .thenComparingInt(location -> location.getRange().end.column);
  }
}
