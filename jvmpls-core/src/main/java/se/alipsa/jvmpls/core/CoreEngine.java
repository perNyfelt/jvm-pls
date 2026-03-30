package se.alipsa.jvmpls.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import se.alipsa.jvmpls.core.model.*;
import se.alipsa.jvmpls.core.types.JvmType;
import se.alipsa.jvmpls.core.types.JvmTypes;
import se.alipsa.jvmpls.core.types.MethodSignature;

/** Default implementation of CoreFacade. */
public final class CoreEngine implements CoreFacade {
  private static final Logger LOG = Logger.getLogger(CoreEngine.class.getName());
  private static final Pattern DOC_COMMENT_PREFIX =
      Pattern.compile("^\\s*(?:/\\*+|\\*+/?|//+)\\s?");

  private final PluginRegistry plugins;
  private final SymbolIndex index;
  private final DocumentStore docs;
  private final DependencyGraph graph;
  private final Executor executor;

  /** Track which plugin currently owns a given URI. */
  private final Map<String, JvmLangPlugin> pluginByUri = new ConcurrentHashMap<>();

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("EI_EXPOSE_REP2")
  public CoreEngine(
      PluginRegistry plugins,
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
      try {
        pl.forget(uri);
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Failed to forget plugin state for " + uri, e);
      }
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
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Completion request failed for " + uri, e);
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
      SymbolInfo sym = pl.resolveSymbol(uri, token, position, index);
      return sym == null ? Optional.empty() : Optional.ofNullable(sym.getLocation());
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Definition request failed for " + uri, e);
      return Optional.empty();
    }
  }

  @Override
  public Optional<HoverInfo> hover(String uri, Position position) {
    String text = docs.get(uri);
    if (text == null) {
      return Optional.empty();
    }
    SymbolInfo declaration = symbolDeclaredAt(uri, position);
    if (declaration != null) {
      return Optional.of(SymbolPresentation.hover(declaration, documentationFor(declaration)));
    }
    JvmLangPlugin plugin = pluginByUri.get(uri);
    if (plugin == null) {
      return Optional.empty();
    }
    try {
      Optional<HoverInfo> pluginHover = plugin.hover(uri, position, index);
      if (pluginHover.isPresent()) {
        return pluginHover;
      }
      int offset = TokenUtil.positionToOffset(text, position.line, position.column);
      String token = TokenUtil.tokenAt(text, offset);
      SymbolInfo symbol = plugin.resolveSymbol(uri, token, position, index);
      if (symbol == null) {
        return Optional.empty();
      }
      return Optional.of(SymbolPresentation.hover(symbol, documentationFor(symbol)));
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Hover request failed for " + uri, e);
      return Optional.empty();
    }
  }

  @Override
  public List<Location> references(String uri, Position position, boolean includeDeclaration) {
    String text = docs.get(uri);
    JvmLangPlugin plugin = pluginByUri.get(uri);
    if (text == null || plugin == null) {
      return List.of();
    }
    try {
      SymbolInfo target = symbolDeclaredAt(uri, position);
      if (target == null) {
        int offset = TokenUtil.positionToOffset(text, position.line, position.column);
        String token = TokenUtil.tokenAt(text, offset);
        target = plugin.resolveSymbol(uri, token, position, index);
      }
      if (target == null) {
        return List.of();
      }
      LinkedHashMap<String, Location> results = new LinkedHashMap<>();
      if (includeDeclaration && target.getLocation() != null) {
        results.put(locationKey(target.getLocation()), target.getLocation());
      }
      for (Location location : index.referencesTo(target.getFqName())) {
        results.put(locationKey(location), location);
      }
      return List.copyOf(results.values());
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Reference request failed for " + uri, e);
      return List.of();
    }
  }

  @Override
  public List<SymbolInfo> documentSymbols(String uri) {
    return index.declarationsInFile(uri);
  }

  @Override
  public List<SymbolInfo> workspaceSymbols(String query) {
    return index.search(query, 100);
  }

  @Override
  public Optional<SignatureHelpInfo> signatureHelp(String uri, Position position) {
    JvmLangPlugin plugin = pluginByUri.get(uri);
    if (plugin == null) {
      return Optional.empty();
    }
    try {
      return plugin.signatureHelp(uri, position, index);
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Signature help request failed for " + uri, e);
      return Optional.empty();
    }
  }

  @Override
  public List<CodeActionInfo> codeActions(String uri, Range range, List<Diagnostic> diagnostics) {
    JvmLangPlugin plugin = pluginByUri.get(uri);
    if (plugin == null) {
      return List.of();
    }
    try {
      return plugin.codeActions(uri, range, diagnostics, index);
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Code action request failed for " + uri, e);
      return List.of();
    }
  }

  // --- internals --------------------------------------------------------------------------------

  private List<Diagnostic> reindex(String uri, String text) {
    var pluginOpt = plugins.forFile(uri, () -> TokenUtil.preview(text));
    if (pluginOpt.isEmpty()) {
      // Clear any stale symbols for this file and report info diagnostic
      index.removeFile(uri);
      return List.of(
          new Diagnostic(
              new Range(new Position(0, 0), new Position(0, 1)),
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
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Plugin indexing failed for " + uri + " using " + plugin.id(), e);
      diags =
          List.of(
              new Diagnostic(
                  new Range(new Position(0, 0), new Position(0, 1)),
                  "Plugin error: " + e.getMessage(),
                  Diagnostic.Severity.ERROR,
                  plugin.id(),
                  "plugin-exception"));
    }
    return diags;
  }

  private SymbolReporter wrapReporter(String uri, String pluginId) {
    return new SymbolReporter() {
      @Override
      public void reportPackage(String pkgFqn, Location loc) {
        index.put(
            uri,
            new SymbolInfo(
                pluginId, SymbolInfo.Kind.PACKAGE, pkgFqn, "", loc, "", Set.of(), List.of()));
      }

      @Override
      public void reportClass(
          String classFqn, Location loc, boolean isInterface, boolean isEnum, boolean isAnno) {
        reportClass(
            classFqn,
            loc,
            isInterface,
            isEnum,
            isAnno,
            SyntheticOrigin.NONE,
            InferenceConfidence.DETERMINISTIC);
      }

      @Override
      public void reportClass(
          String classFqn,
          Location loc,
          boolean isInterface,
          boolean isEnum,
          boolean isAnno,
          SyntheticOrigin origin,
          InferenceConfidence confidence) {
        SymbolInfo.Kind kind =
            isAnno
                ? SymbolInfo.Kind.ANNOTATION
                : isEnum
                    ? SymbolInfo.Kind.ENUM
                    : isInterface ? SymbolInfo.Kind.INTERFACE : SymbolInfo.Kind.CLASS;
        String container =
            classFqn.contains(".") ? classFqn.substring(0, classFqn.lastIndexOf('.')) : "";
        index.put(
            uri,
            new SymbolInfo(
                pluginId,
                kind,
                classFqn,
                container,
                loc,
                "",
                Set.of(),
                List.of(),
                null,
                null,
                origin,
                confidence));
      }

      @Override
      public void reportMethod(
          String ownerClassFqn, String methodName, String signature, Location loc) {
        MethodSignature typed = JvmTypes.fromLegacyMethodSignature(signature, Set.of());
        reportMethod(ownerClassFqn, methodName, typed, loc, Set.of());
      }

      @Override
      public void reportField(
          String ownerClassFqn, String fieldName, String typeFqn, Location loc) {
        JvmType typed = JvmTypes.fromSource(typeFqn, Function.identity());
        reportField(ownerClassFqn, fieldName, typed, loc, Set.of());
      }

      @Override
      public void reportAnnotation(String annotationFqn, Location loc) {
        index.put(
            uri,
            new SymbolInfo(
                pluginId,
                SymbolInfo.Kind.ANNOTATION,
                annotationFqn,
                "",
                loc,
                "",
                Set.of(),
                List.of()));
      }

      @Override
      public void reportMethod(
          String ownerClassFqn,
          String methodName,
          MethodSignature signature,
          Location loc,
          Set<String> modifiers) {
        reportMethod(
            ownerClassFqn,
            methodName,
            signature,
            loc,
            modifiers,
            SyntheticOrigin.NONE,
            InferenceConfidence.DETERMINISTIC);
      }

      @Override
      public void reportMethod(
          String ownerClassFqn,
          String methodName,
          MethodSignature signature,
          Location loc,
          Set<String> modifiers,
          SyntheticOrigin origin,
          InferenceConfidence confidence) {
        String legacySignature = JvmTypes.toLegacyMethodSignature(signature);
        String fqn = ownerClassFqn + "#" + methodName + legacySignature;
        index.put(
            uri,
            new SymbolInfo(
                pluginId,
                SymbolInfo.Kind.METHOD,
                fqn,
                ownerClassFqn,
                loc,
                legacySignature,
                modifiers,
                signature.typeParameters(),
                null,
                signature,
                origin,
                confidence));
      }

      @Override
      public void reportConstructor(
          String ownerClassFqn, MethodSignature signature, Location loc, Set<String> modifiers) {
        reportConstructor(
            ownerClassFqn,
            signature,
            loc,
            modifiers,
            SyntheticOrigin.NONE,
            InferenceConfidence.DETERMINISTIC);
      }

      @Override
      public void reportConstructor(
          String ownerClassFqn,
          MethodSignature signature,
          Location loc,
          Set<String> modifiers,
          SyntheticOrigin origin,
          InferenceConfidence confidence) {
        String legacySignature = JvmTypes.toLegacyMethodSignature(signature);
        String fqn = ownerClassFqn + "#<init>" + legacySignature;
        index.put(
            uri,
            new SymbolInfo(
                pluginId,
                SymbolInfo.Kind.CONSTRUCTOR,
                fqn,
                ownerClassFqn,
                loc,
                legacySignature,
                modifiers,
                signature.typeParameters(),
                null,
                signature,
                origin,
                confidence));
      }

      @Override
      public void reportField(
          String ownerClassFqn,
          String fieldName,
          JvmType type,
          Location loc,
          Set<String> modifiers) {
        reportField(
            ownerClassFqn,
            fieldName,
            type,
            loc,
            modifiers,
            SyntheticOrigin.NONE,
            InferenceConfidence.DETERMINISTIC);
      }

      @Override
      public void reportField(
          String ownerClassFqn,
          String fieldName,
          JvmType type,
          Location loc,
          Set<String> modifiers,
          SyntheticOrigin origin,
          InferenceConfidence confidence) {
        String fqn = ownerClassFqn + "." + fieldName;
        index.put(
            uri,
            new SymbolInfo(
                pluginId,
                SymbolInfo.Kind.FIELD,
                fqn,
                ownerClassFqn,
                loc,
                type.displayName(),
                modifiers,
                List.of(),
                type,
                null,
                origin,
                confidence));
      }

      @Override
      public void reportReference(String targetFqn, Location useSite) {
        index.reportReference(uri, targetFqn, useSite);
      }
    };
  }

  private SymbolInfo symbolDeclaredAt(String uri, Position position) {
    return index.declarationsInFile(uri).stream()
        .filter(symbol -> contains(symbol.getLocation(), position))
        .min(
            Comparator.comparingInt(
                symbol ->
                    spanLength(
                        symbol.getLocation() == null ? null : symbol.getLocation().getRange())))
        .orElse(null);
  }

  private String documentationFor(SymbolInfo symbol) {
    Location location = symbol.getLocation();
    if (location == null) {
      return "";
    }
    String source = docs.get(location.getUri());
    if ((source == null || source.isBlank()) && location.getUri().startsWith("file:")) {
      try {
        source =
            java.nio.file.Files.readString(
                java.nio.file.Path.of(java.net.URI.create(location.getUri())));
      } catch (Exception ignored) {
        source = null;
      }
    }
    if (source == null || source.isBlank()) {
      return "";
    }
    return extractDocComment(source, location.getRange().start.line);
  }

  private static String extractDocComment(String source, int declarationLine) {
    String[] lines = source.split("\\R", -1);
    if (declarationLine <= 0 || declarationLine > lines.length) {
      return "";
    }
    int line = declarationLine - 1;
    while (line >= 0 && lines[line].isBlank()) {
      line--;
    }
    if (line < 0) {
      return "";
    }
    List<String> docLines = new ArrayList<>();
    if (lines[line].stripLeading().startsWith("//")) {
      while (line >= 0 && lines[line].stripLeading().startsWith("//")) {
        docLines.add(lines[line]);
        line--;
      }
      Collections.reverse(docLines);
      return sanitizeDocLines(docLines);
    }
    if (!lines[line].contains("*/")) {
      return "";
    }
    while (line >= 0) {
      docLines.add(lines[line]);
      if (lines[line].contains("/*")) {
        Collections.reverse(docLines);
        return sanitizeDocLines(docLines);
      }
      line--;
    }
    return "";
  }

  private static String sanitizeDocLines(List<String> docLines) {
    return docLines.stream()
        .map(line -> DOC_COMMENT_PREFIX.matcher(line.strip()).replaceFirst(""))
        .map(line -> line.replaceFirst("\\*/\\s*$", "").strip())
        .filter(line -> !line.isBlank())
        .reduce((left, right) -> left + "\n" + right)
        .orElse("");
  }

  private static boolean contains(Location location, Position position) {
    if (location == null || location.getRange() == null) {
      return false;
    }
    Range range = location.getRange();
    return compare(position, range.start) >= 0 && compare(position, range.end) <= 0;
  }

  private static int compare(Position left, Position right) {
    int byLine = Integer.compare(left.line, right.line);
    return byLine != 0 ? byLine : Integer.compare(left.column, right.column);
  }

  private static int spanLength(Range range) {
    if (range == null || range.start == null || range.end == null) {
      return Integer.MAX_VALUE;
    }
    return (range.end.line - range.start.line) * 10_000 + (range.end.column - range.start.column);
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
}
