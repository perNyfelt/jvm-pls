package se.alipsa.jvmpls.core;

import se.alipsa.jvmpls.core.model.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** Default implementation of CoreFacade. */
public final class CoreEngine implements CoreFacade {

  private final PluginRegistry plugins;
  private final SymbolIndex index;
  private final DocumentStore docs;
  private final DependencyGraph graph;
  private final Executor executor;

  /** Track which plugin currently owns a given URI. */
  private final Map<String, JvmLangPlugin> pluginByUri = new ConcurrentHashMap<>();

  public CoreEngine(PluginRegistry plugins,
                    SymbolIndex index,
                    DocumentStore docs,
                    DependencyGraph graph,
                    Executor executor) {
    this.plugins = Objects.requireNonNull(plugins);
    this.index = Objects.requireNonNull(index);
    this.docs = Objects.requireNonNull(docs);
    this.graph = Objects.requireNonNull(graph);
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public List<Diagnostic> openFile(String uri, String text) {
    docs.put(uri, text);
    return reindex(uri, text);
  }

  @Override
  public List<Diagnostic> changeFile(String uri, String text) {
    docs.put(uri, text);
    return reindex(uri, text);
  }

  @Override
  public void closeFile(String uri) {
    docs.remove(uri);
    index.removeFile(uri);
    graph.removeFile(uri);
    var pl = pluginByUri.remove(uri);
    if (pl != null) {
      try { pl.forget(uri); } catch (Throwable ignored) {}
    }
  }

  @Override
  public List<Diagnostic> analyze(String uri) {
    String text = docs.get(uri);
    if (text == null) return List.of();
    return reindex(uri, text);
  }

  @Override
  public List<CompletionItem> completions(String uri, Position position) {
    var pl = pluginByUri.get(uri);
    if (pl == null) return List.of();
    try {
      return pl.completions(uri, position, index);
    } catch (Throwable t) {
      return List.of();
    }
  }

  @Override
  public Optional<Location> definition(String uri, Position position) {
    var pl = pluginByUri.get(uri);
    String text = docs.get(uri);
    if (pl == null || text == null) return Optional.empty();

    int offset = TokenUtil.positionToOffset(text, position.line, position.column);
    String token = TokenUtil.tokenAt(text, offset);

    try {
      SymbolInfo sym = pl.resolveSymbol(uri, token, index);
      return sym == null ? Optional.empty() : Optional.ofNullable(sym.getLocation());
    } catch (Throwable t) {
      return Optional.empty();
    }
  }

  // --- internals --------------------------------------------------------------------------------

  private List<Diagnostic> reindex(String uri, String text) {
    var pluginOpt = plugins.forFile(uri, () -> TokenUtil.preview(text));
    if (pluginOpt.isEmpty()) {
      // Clear any stale symbols for this file and report info diagnostic
      index.removeFile(uri);
      return List.of(new Diagnostic(
          new Range(new Position(0,0), new Position(0,1)),
          "No plugin registered to handle " + uri,
          Diagnostic.Severity.INFORMATION,
          "core",
          "no-plugin"));
    }

    JvmLangPlugin plugin = pluginOpt.get();
    pluginByUri.put(uri, plugin);

    SymbolReporter reporter = wrapReporter(uri, plugin.id());
    List<Diagnostic> diags;
    try {
      diags = plugin.index(uri, text, reporter);
    } catch (Throwable t) {
      diags = List.of(new Diagnostic(
          new Range(new Position(0,0), new Position(0,1)),
          "Plugin error: " + t.getMessage(),
          Diagnostic.Severity.ERROR,
          plugin.id(),
          "plugin-exception"));
    }
    return diags;
  }

  private SymbolReporter wrapReporter(String uri, String pluginId) {
    return new SymbolReporter() {
      @Override public void reportPackage(String pkgFqn, Location loc) {
        index.put(uri, new SymbolInfo(pluginId, SymbolInfo.Kind.PACKAGE, pkgFqn, "", loc, "", Set.of(), List.of()));
      }
      @Override public void reportClass(String classFqn, Location loc, boolean isInterface, boolean isEnum, boolean isAnno) {
        SymbolInfo.Kind kind = isAnno ? SymbolInfo.Kind.ANNOTATION
            : isEnum ? SymbolInfo.Kind.ENUM
            : isInterface ? SymbolInfo.Kind.INTERFACE : SymbolInfo.Kind.CLASS;
        String container = classFqn.contains(".") ? classFqn.substring(0, classFqn.lastIndexOf('.')) : "";
        index.put(uri, new SymbolInfo(pluginId, kind, classFqn, container, loc, "", Set.of(), List.of()));
      }
      @Override public void reportMethod(String ownerClassFqn, String methodName, String signature, Location loc) {
        String fqn = ownerClassFqn + "#" + methodName + signature;
        index.put(uri, new SymbolInfo(pluginId, SymbolInfo.Kind.METHOD, fqn, ownerClassFqn, loc, signature, Set.of(), List.of()));
      }
      @Override public void reportField(String ownerClassFqn, String fieldName, String typeFqn, Location loc) {
        String fqn = ownerClassFqn + "." + fieldName;
        index.put(uri, new SymbolInfo(pluginId, SymbolInfo.Kind.FIELD, fqn, ownerClassFqn, loc, typeFqn, Set.of(), List.of()));
      }
      @Override public void reportAnnotation(String annotationFqn, Location loc) {
        index.put(uri, new SymbolInfo(pluginId, SymbolInfo.Kind.ANNOTATION, annotationFqn, "", loc, "", Set.of(), List.of()));
      }
    };
  }
}
