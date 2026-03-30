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
    try {
      SymbolInfo symbol = resolveTarget(uri, position);
      return symbol == null ? Optional.empty() : Optional.ofNullable(symbol.getLocation());
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Definition request failed for " + uri, e);
      return Optional.empty();
    }
  }

  @Override
  public Optional<Location> typeDefinition(String uri, Position position) {
    JvmLangPlugin plugin = pluginByUri.get(uri);
    if (plugin == null) {
      return Optional.empty();
    }
    try {
      Optional<SymbolInfo> typeSymbol = plugin.typeDefinition(uri, position, index);
      if (typeSymbol.isPresent()) {
        return Optional.ofNullable(typeSymbol.get().getLocation());
      }
      SymbolInfo symbol = resolveTarget(uri, position);
      return symbol == null ? Optional.empty() : declaredTypeLocation(symbol);
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Type definition request failed for " + uri, e);
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
  public List<Location> implementations(String uri, Position position) {
    try {
      SymbolInfo target = resolveTarget(uri, position);
      if (target == null) {
        return List.of();
      }
      if (isType(target)) {
        return allSubtypeSymbols(target.getFqName()).stream()
            .map(SymbolInfo::getLocation)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
      }
      if (target.getKind() != SymbolInfo.Kind.METHOD) {
        return List.of();
      }
      List<Location> locations = new ArrayList<>();
      for (SymbolInfo subtype : allSubtypeSymbols(target.getContainerFqName())) {
        for (SymbolInfo member : index.membersOf(subtype.getFqName())) {
          if (overrides(target, member) && member.getLocation() != null) {
            locations.add(member.getLocation());
          }
        }
      }
      return locations.stream().distinct().toList();
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Implementation request failed for " + uri, e);
      return List.of();
    }
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

  @Override
  public Optional<PrepareRenameInfo> prepareRename(String uri, Position position) {
    try {
      SymbolInfo target = resolveTarget(uri, position);
      if (target == null || !isRenameSupported(target)) {
        return Optional.empty();
      }
      String currentName = SymbolPresentation.simpleName(target);
      Range range = editableRangeForOccurrence(uri, position, target, currentName);
      if (range == null) {
        return Optional.empty();
      }
      return Optional.of(new PrepareRenameInfo(range, currentName));
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Prepare rename request failed for " + uri, e);
      return Optional.empty();
    }
  }

  @Override
  public Optional<RenamePlan> rename(String uri, Position position, String newName) {
    try {
      SymbolInfo target = resolveTarget(uri, position);
      if (target == null || !isRenameSupported(target)) {
        return Optional.empty();
      }
      String currentName = SymbolPresentation.simpleName(target);
      Range editableRange = editableRangeForOccurrence(uri, position, target, currentName);
      if (editableRange == null) {
        return Optional.empty();
      }
      ArrayList<RenameConflict> conflicts = new ArrayList<>();
      if (!isValidIdentifier(newName)) {
        conflicts.add(
            new RenameConflict(
                "invalid-name", "Rename target must be a valid identifier", target.getLocation()));
      }
      if (target.getKind() == SymbolInfo.Kind.CONSTRUCTOR) {
        conflicts.add(
            new RenameConflict(
                "unsupported-kind",
                "Constructors can only be renamed by renaming the owning type",
                target.getLocation()));
      }
      conflicts.addAll(renameConflicts(target, newName));

      Map<String, List<TextEdit>> edits = new LinkedHashMap<>();
      collectRenameEdits(edits, target, currentName, newName);
      if (isType(target)) {
        for (SymbolInfo constructor : index.constructorsOf(target.getFqName())) {
          collectRenameEdits(edits, constructor, currentName, newName);
        }
      }

      RenamePlan plan =
          new RenamePlan(
              target,
              new PrepareRenameInfo(editableRange, currentName),
              new WorkspaceEditInfo(edits),
              conflicts);
      return Optional.of(plan);
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Rename request failed for " + uri, e);
      return Optional.empty();
    }
  }

  @Override
  public List<CallHierarchyItemInfo> prepareCallHierarchy(String uri, Position position) {
    try {
      SymbolInfo target = resolveTarget(uri, position);
      if (target == null
          || (target.getKind() != SymbolInfo.Kind.METHOD
              && target.getKind() != SymbolInfo.Kind.CONSTRUCTOR)) {
        return List.of();
      }
      return List.of(callHierarchyItem(target));
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Prepare call hierarchy request failed for " + uri, e);
      return List.of();
    }
  }

  @Override
  public List<IncomingCallInfo> incomingCalls(String symbolFqn) {
    try {
      return incomingCallInfos(index.incomingCallsTo(symbolFqn));
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Incoming call hierarchy request failed for " + symbolFqn, e);
      return List.of();
    }
  }

  @Override
  public List<OutgoingCallInfo> outgoingCalls(String symbolFqn) {
    try {
      return outgoingCallInfos(index.outgoingCallsFrom(symbolFqn));
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Outgoing call hierarchy request failed for " + symbolFqn, e);
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

      @Override
      public void reportDirectSupertypes(String typeFqn, List<String> directSupertypes) {
        index.reportDirectSupertypes(typeFqn, directSupertypes);
      }

      @Override
      public void reportCall(String callerFqn, String calleeFqn, Location useSite) {
        index.reportCall(uri, callerFqn, calleeFqn, useSite);
      }
    };
  }

  private SymbolInfo resolveTarget(String uri, Position position) {
    SymbolInfo declaration = symbolDeclaredAt(uri, position);
    if (declaration != null) {
      return declaration;
    }
    JvmLangPlugin plugin = pluginByUri.get(uri);
    String text = docs.get(uri);
    if (plugin == null || text == null) {
      return null;
    }
    int offset = TokenUtil.positionToOffset(text, position.line, position.column);
    String token = TokenUtil.tokenAt(text, offset);
    return plugin.resolveSymbol(uri, token, position, index);
  }

  private Optional<Location> typeLocation(JvmType type) {
    if (type instanceof se.alipsa.jvmpls.core.types.ClassType classType) {
      return index.findByFqn(classType.fqName()).map(SymbolInfo::getLocation);
    }
    return Optional.empty();
  }

  private Optional<Location> declaredTypeLocation(SymbolInfo symbol) {
    if (symbol.getKind() == SymbolInfo.Kind.CONSTRUCTOR) {
      return index.findByFqn(symbol.getContainerFqName()).map(SymbolInfo::getLocation);
    }
    if (symbol.getKind() == SymbolInfo.Kind.METHOD && symbol.getMethodSignature() != null) {
      return typeLocation(symbol.getMethodSignature().returnType());
    }
    if (symbol.getResolvedType() != null) {
      return typeLocation(symbol.getResolvedType());
    }
    return Optional.empty();
  }

  private List<SymbolInfo> allSubtypeSymbols(String typeFqn) {
    LinkedHashMap<String, SymbolInfo> results = new LinkedHashMap<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    HashSet<String> seen = new HashSet<>();
    queue.add(typeFqn);
    seen.add(typeFqn);
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      for (SymbolInfo subtype : index.directSubtypesOf(current)) {
        if (seen.add(subtype.getFqName())) {
          results.put(subtype.getFqName(), subtype);
          queue.addLast(subtype.getFqName());
        }
      }
    }
    return List.copyOf(results.values());
  }

  private static boolean overrides(SymbolInfo base, SymbolInfo candidate) {
    if (candidate.getKind() != SymbolInfo.Kind.METHOD) {
      return false;
    }
    if (!SymbolPresentation.simpleName(base).equals(SymbolPresentation.simpleName(candidate))) {
      return false;
    }
    MethodSignature left = base.getMethodSignature();
    MethodSignature right = candidate.getMethodSignature();
    if (left == null || right == null) {
      return base.getSignature().equals(candidate.getSignature());
    }
    return left.parameterTypes().size() == right.parameterTypes().size();
  }

  private static boolean isType(SymbolInfo symbol) {
    return symbol.getKind() == SymbolInfo.Kind.CLASS
        || symbol.getKind() == SymbolInfo.Kind.INTERFACE
        || symbol.getKind() == SymbolInfo.Kind.ENUM
        || symbol.getKind() == SymbolInfo.Kind.ANNOTATION;
  }

  private static boolean isRenameSupported(SymbolInfo symbol) {
    return switch (symbol.getKind()) {
      case CLASS, INTERFACE, ENUM, ANNOTATION, METHOD, FIELD -> true;
      default -> false;
    };
  }

  private List<RenameConflict> renameConflicts(SymbolInfo target, String newName) {
    if (newName == null || newName.isBlank()) {
      return List.of(
          new RenameConflict(
              "invalid-name", "Rename target cannot be blank", target.getLocation()));
    }
    if (newName.equals(SymbolPresentation.simpleName(target))) {
      return List.of();
    }
    ArrayList<RenameConflict> conflicts = new ArrayList<>();
    if (isType(target)) {
      String pkg = packageName(target.getFqName());
      boolean conflict =
          index.allInPackage(pkg).stream()
              .filter(symbol -> isType(symbol))
              .anyMatch(
                  symbol ->
                      !symbol.getFqName().equals(target.getFqName())
                          && newName.equals(SymbolPresentation.simpleName(symbol)));
      if (conflict) {
        conflicts.add(
            new RenameConflict(
                "name-conflict",
                "Another type named '" + newName + "' already exists in " + pkg,
                target.getLocation()));
      }
      return conflicts;
    }
    boolean conflict =
        index.membersOf(target.getContainerFqName()).stream()
            .filter(symbol -> symbol.getKind() == target.getKind())
            .anyMatch(
                symbol ->
                    !symbol.getFqName().equals(target.getFqName())
                        && newName.equals(SymbolPresentation.simpleName(symbol)));
    if (conflict) {
      conflicts.add(
          new RenameConflict(
              "name-conflict",
              "Another member named '"
                  + newName
                  + "' already exists in "
                  + target.getContainerFqName(),
              target.getLocation()));
    }
    return conflicts;
  }

  private void collectRenameEdits(
      Map<String, List<TextEdit>> edits, SymbolInfo target, String oldName, String newName) {
    addRenameEdit(edits, target.getLocation(), target, oldName, newName);
    for (Location reference : index.referencesTo(target.getFqName())) {
      addRenameEdit(edits, reference, target, oldName, newName);
    }
  }

  private void addRenameEdit(
      Map<String, List<TextEdit>> edits,
      Location location,
      SymbolInfo target,
      String oldName,
      String newName) {
    if (location == null || location.getRange() == null) {
      return;
    }
    String source = readSource(location.getUri());
    if (source == null) {
      return;
    }
    Range range = editableRangeInLocation(source, location.getRange(), target, oldName);
    if (range == null) {
      return;
    }
    edits
        .computeIfAbsent(location.getUri(), ignored -> new ArrayList<>())
        .add(new TextEdit(range, newName));
  }

  private Range editableRangeForOccurrence(
      String uri, Position position, SymbolInfo target, String oldName) {
    String source = readSource(uri);
    if (source == null) {
      return null;
    }
    Range exact = tokenRangeAt(source, position);
    if (exact != null) {
      return exact;
    }
    Location location = target.getLocation();
    if (location == null || !uri.equals(location.getUri())) {
      return null;
    }
    return editableRangeInLocation(source, location.getRange(), target, oldName);
  }

  private String readSource(String uri) {
    String source = docs.get(uri);
    if ((source == null || source.isBlank()) && uri != null && uri.startsWith("file:")) {
      try {
        source = java.nio.file.Files.readString(java.nio.file.Path.of(java.net.URI.create(uri)));
      } catch (Exception ignored) {
        source = null;
      }
    }
    return source;
  }

  private Range editableRangeInLocation(
      String source, Range broadRange, SymbolInfo target, String oldName) {
    if (source == null || broadRange == null) {
      return null;
    }
    int startOffset =
        TokenUtil.positionToOffset(source, broadRange.start.line, broadRange.start.column);
    int endOffset = TokenUtil.positionToOffset(source, broadRange.end.line, broadRange.end.column);
    if (endOffset <= startOffset) {
      endOffset = Math.min(source.length(), startOffset + Math.max(1, oldName.length() + 8));
    }
    String snippet =
        source.substring(Math.max(0, startOffset), Math.min(source.length(), endOffset));
    int index = bestIdentifierMatch(snippet, target, oldName);
    if (index < 0) {
      return null;
    }
    return offsetRange(source, startOffset + index, oldName.length());
  }

  private static int bestIdentifierMatch(String snippet, SymbolInfo target, String oldName) {
    if (snippet == null || snippet.isBlank() || oldName == null || oldName.isBlank()) {
      return -1;
    }
    List<Integer> matches = identifierMatches(snippet, oldName);
    if (matches.isEmpty()) {
      return -1;
    }
    if (matches.size() == 1) {
      return matches.getFirst();
    }
    if (target.getKind() == SymbolInfo.Kind.METHOD || target.getKind() == SymbolInfo.Kind.FIELD) {
      return matches.getLast();
    }
    String dotted = "." + oldName;
    int dottedIndex = snippet.lastIndexOf(dotted);
    if (dottedIndex >= 0) {
      return dottedIndex + 1;
    }
    String ctor = "new " + oldName;
    int ctorIndex = snippet.lastIndexOf(ctor);
    if (ctorIndex >= 0) {
      return ctorIndex + 4;
    }
    return matches.getLast();
  }

  private static List<Integer> identifierMatches(String snippet, String name) {
    ArrayList<Integer> matches = new ArrayList<>();
    int from = 0;
    while (from >= 0 && from < snippet.length()) {
      int hit = snippet.indexOf(name, from);
      if (hit < 0) {
        break;
      }
      boolean startOk = hit == 0 || !Character.isJavaIdentifierPart(snippet.charAt(hit - 1));
      int endIndex = hit + name.length();
      boolean endOk =
          endIndex >= snippet.length() || !Character.isJavaIdentifierPart(snippet.charAt(endIndex));
      if (startOk && endOk) {
        matches.add(hit);
      }
      from = hit + name.length();
    }
    return matches;
  }

  private Range tokenRangeAt(String source, Position position) {
    int offset = TokenUtil.positionToOffset(source, position.line, position.column);
    if (source.isEmpty()) {
      return null;
    }
    int i = Math.max(0, Math.min(offset, source.length() - 1));
    if (!Character.isJavaIdentifierPart(source.charAt(i))
        && i > 0
        && Character.isJavaIdentifierPart(source.charAt(i - 1))) {
      i--;
    }
    if (!Character.isJavaIdentifierPart(source.charAt(i))) {
      return null;
    }
    int start = i;
    int end = i + 1;
    while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) {
      start--;
    }
    while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) {
      end++;
    }
    return offsetRange(source, start, end - start);
  }

  private static Range offsetRange(String source, int startOffset, int length) {
    Position start = offsetToPosition(source, startOffset);
    Position end = offsetToPosition(source, startOffset + length);
    return new Range(start, end);
  }

  private static Position offsetToPosition(String source, int targetOffset) {
    int offset = Math.max(0, Math.min(targetOffset, source.length()));
    int line = 0;
    int column = 0;
    for (int i = 0; i < offset; i++) {
      if (source.charAt(i) == '\n') {
        line++;
        column = 0;
      } else {
        column++;
      }
    }
    return new Position(line, column);
  }

  private static boolean isValidIdentifier(String candidate) {
    if (candidate == null || candidate.isBlank()) {
      return false;
    }
    if (!Character.isJavaIdentifierStart(candidate.charAt(0))) {
      return false;
    }
    for (int i = 1; i < candidate.length(); i++) {
      if (!Character.isJavaIdentifierPart(candidate.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static String packageName(String fqn) {
    int hash = fqn.indexOf('#');
    String typeFqn = hash < 0 ? fqn : fqn.substring(0, hash);
    int lastDot = typeFqn.lastIndexOf('.');
    return lastDot < 0 ? "" : typeFqn.substring(0, lastDot);
  }

  private CallHierarchyItemInfo callHierarchyItem(SymbolInfo symbol) {
    return new CallHierarchyItemInfo(
        symbol.getFqName(),
        SymbolPresentation.simpleName(symbol),
        symbol.getContainerFqName(),
        symbol.getKind(),
        symbol.getLocation(),
        symbol.getLocation() == null ? null : symbol.getLocation().getRange());
  }

  private List<IncomingCallInfo> incomingCallInfos(Map<String, List<Location>> calls) {
    if (calls.isEmpty()) {
      return List.of();
    }
    ArrayList<IncomingCallInfo> incomingCalls = new ArrayList<>();
    calls.forEach(
        (symbolFqn, locations) ->
            index
                .findByFqn(symbolFqn)
                .ifPresent(
                    symbol -> {
                      CallHierarchyItemInfo item = callHierarchyItem(symbol);
                      List<Range> ranges =
                          locations.stream()
                              .map(Location::getRange)
                              .filter(Objects::nonNull)
                              .toList();
                      incomingCalls.add(new IncomingCallInfo(item, ranges));
                    }));
    return incomingCalls;
  }

  private List<OutgoingCallInfo> outgoingCallInfos(Map<String, List<Location>> calls) {
    if (calls.isEmpty()) {
      return List.of();
    }
    ArrayList<OutgoingCallInfo> outgoingCalls = new ArrayList<>();
    calls.forEach(
        (symbolFqn, locations) ->
            index
                .findByFqn(symbolFqn)
                .ifPresent(
                    symbol -> {
                      CallHierarchyItemInfo item = callHierarchyItem(symbol);
                      List<Range> ranges =
                          locations.stream()
                              .map(Location::getRange)
                              .filter(Objects::nonNull)
                              .toList();
                      outgoingCalls.add(new OutgoingCallInfo(item, ranges));
                    }));
    return outgoingCalls;
  }

  private SymbolInfo symbolDeclaredAt(String uri, Position position) {
    String source = readSource(uri);
    return index.declarationsInFile(uri).stream()
        .filter(
            symbol -> {
              if (symbol.getLocation() == null || !uri.equals(symbol.getLocation().getUri())) {
                return false;
              }
              Range nameRange =
                  source == null
                      ? null
                      : editableRangeInLocation(
                          source,
                          symbol.getLocation().getRange(),
                          symbol,
                          SymbolPresentation.simpleName(symbol));
              return contains(
                  new Location(
                      uri, nameRange == null ? symbol.getLocation().getRange() : nameRange),
                  position);
            })
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
