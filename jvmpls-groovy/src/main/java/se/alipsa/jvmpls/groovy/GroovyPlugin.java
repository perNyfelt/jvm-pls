package se.alipsa.jvmpls.groovy;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.builder.AstBuilder;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.JvmLangPlugin;
import se.alipsa.jvmpls.core.PluginEnvironment;
import se.alipsa.jvmpls.core.SymbolReporter;
import se.alipsa.jvmpls.core.model.*;
import se.alipsa.jvmpls.core.types.ArrayType;
import se.alipsa.jvmpls.core.types.ClassType;
import se.alipsa.jvmpls.core.types.DynamicType;
import se.alipsa.jvmpls.core.types.JvmType;
import se.alipsa.jvmpls.core.types.JvmTypes;
import se.alipsa.jvmpls.core.types.MethodSignature;
import se.alipsa.jvmpls.core.types.PrimitiveType;
import se.alipsa.jvmpls.core.types.TypeResolver;
import se.alipsa.jvmpls.core.types.VoidType;
import se.alipsa.jvmpls.groovy.dynamic.GroovyMemberResolver;
import se.alipsa.jvmpls.groovy.dynamic.ScopedSyntheticMember;
import se.alipsa.jvmpls.groovy.transforms.TransformContext;
import se.alipsa.jvmpls.groovy.transforms.TransformRegistry;

public final class GroovyPlugin implements JvmLangPlugin {
  private static final Logger LOG = Logger.getLogger(GroovyPlugin.class.getName());

  @Override
  public String id() {
    return "groovy";
  }

  @Override
  public Set<String> fileExtensions() {
    return Set.of("groovy", "gvy", "gy", "gsh");
  }

  private static final Pattern PKG_DECL =
      Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;?\\s*$");
  private static final Pattern STAR_SUFFIX = Pattern.compile("\\.\\*");
  // Groovy default single-type imports (no import line should be suggested)
  private static final Set<String> DEFAULT_SINGLE_IMPORTS =
      Set.of("java.math.BigInteger", "java.math.BigDecimal");

  // Keep per-file context for resolveSymbol (package & imports)
  private static final class FileCtx {
    String pkg = "";
    final List<String> singleImports = new ArrayList<>(); // a.b.C
    final List<String> starImports = new ArrayList<>(); // a.b (package)
    final Map<String, String> aliasToFqn = new HashMap<>(); // alias -> FQN
    String primaryClassFqn = "";
  }

  private record ClassScope(String ownerFqn, Range range) {}

  // Per-file state is replaced/cleared atomically after each index/forget pass.
  // Core may index different files concurrently, but each file is analyzed on one thread at a time.
  private final Map<String, FileCtx> ctxByUri = new ConcurrentHashMap<>();
  private final Map<String, String> contentByUri = new ConcurrentHashMap<>();
  private final Map<String, List<String>> directSupertypesByType = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> typesByUri = new ConcurrentHashMap<>();
  private final Map<String, List<ClassScope>> classScopesByUri = new ConcurrentHashMap<>();
  private final Map<String, List<ScopedSyntheticMember>> scopedMembersByUri =
      new ConcurrentHashMap<>();
  private final Map<String, Set<String>> dynamicMethodTypesByUri = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> dynamicPropertyTypesByUri = new ConcurrentHashMap<>();
  private final Map<String, List<Range>> strictStaticScopesByUri = new ConcurrentHashMap<>();
  private final Map<String, List<Range>> dynamicRelaxedScopesByUri = new ConcurrentHashMap<>();
  private final Set<String> missingCoreWarnings = ConcurrentHashMap.newKeySet();
  private final TransformRegistry transformRegistry = new TransformRegistry();
  private volatile CoreQuery coreQuery;
  private volatile TypeResolver typeResolver;

  // Groovy default star imports (visibility without explicit imports)
  private static final List<String> DEFAULT_STAR_IMPORTS =
      List.of("java.lang", "java.util", "java.io", "java.net", "groovy.lang", "groovy.util");

  @Override
  public void configure(PluginEnvironment env) {
    coreQuery = env.core();
    typeResolver = new TypeResolver(env.core());
    missingCoreWarnings.clear();
  }

  @Override
  public List<Diagnostic> index(String fileUri, String content, SymbolReporter reporter) {
    contentByUri.put(fileUri, content);
    clearHierarchy(fileUri);
    classScopesByUri.remove(fileUri);
    scopedMembersByUri.remove(fileUri);
    dynamicMethodTypesByUri.remove(fileUri);
    dynamicPropertyTypesByUri.remove(fileUri);
    strictStaticScopesByUri.remove(fileUri);
    dynamicRelaxedScopesByUri.remove(fileUri);
    var diags = new ArrayList<Diagnostic>();
    var fileCtx = new FileCtx();
    hydrateCtxFromSource(fileCtx, content);

    try {
      // Parse to at least CONVERSION so ClassNode/MethodNode are populated.
      List<ASTNode> nodes =
          new AstBuilder().buildFromString(CompilePhase.CONVERSION, false, content);
      LinkedHashMap<String, ClassNode> classes = new LinkedHashMap<>();
      ArrayList<ModuleNode> modules = new ArrayList<>();
      for (ASTNode n : nodes) {
        if (n instanceof ModuleNode mn) {
          modules.add(mn);
          hydrateCtxFromModule(fileCtx, mn, fileUri, reporter);
          for (ClassNode cn : mn.getClasses()) {
            classes.putIfAbsent(cn.getName(), cn);
          }
        } else if (n instanceof ClassNode cn) {
          classes.putIfAbsent(cn.getName(), cn);
        }
      }
      for (ClassNode cn : classes.values()) {
        visitClass(fileUri, cn, reporter, fileCtx);
      }
      for (ClassNode cn : classes.values()) {
        applyTransforms(fileUri, cn, reporter, fileCtx);
      }
      analyzeDynamicFeatures(fileUri, classes.values(), modules, reporter, fileCtx);
      runSemanticDiagnostics(fileUri, classes.values(), diags, fileCtx);

    } catch (MultipleCompilationErrorsException mce) {
      // Convert Groovy compiler errors to our Diagnostic model
      for (Message msg : mce.getErrorCollector().getErrors()) {
        if (msg instanceof SyntaxErrorMessage sem) {
          SyntaxException se = sem.getCause();
          Range r =
              fromGroovyPos(
                  se.getStartLine(), se.getStartColumn(), se.getLine(), se.getEndColumn());
          diags.add(
              new Diagnostic(
                  r, se.getOriginalMessage(), Diagnostic.Severity.ERROR, id(), "syntax"));
        } else {
          diags.add(
              new Diagnostic(
                  new Range(new Position(0, 0), new Position(0, 1)),
                  msg.toString(),
                  Diagnostic.Severity.ERROR,
                  id(),
                  "error"));
        }
      }
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Failed to index Groovy file " + fileUri, e);
      diags.add(
          new Diagnostic(
              new Range(new Position(0, 0), new Position(0, 1)),
              "Parse error: " + e.getMessage(),
              Diagnostic.Severity.ERROR,
              id(),
              "parse"));
    }

    // Save ctx after successful/attempted parse (best-effort for resolveSymbol/completions)
    ctxByUri.put(fileUri, fileCtx);
    return diags;
  }

  @Override
  public SymbolInfo resolveSymbol(String fileUri, String symbolName, CoreQuery core) {
    return resolveSymbol(fileUri, symbolName, null, core);
  }

  @Override
  public SymbolInfo resolveSymbol(
      String fileUri, String symbolName, Position position, CoreQuery core) {
    if (symbolName == null || symbolName.isBlank()) return null;

    // Try full FQN first (e.g., demo.Hello)
    int dot = symbolName.indexOf('.');
    if (dot >= 0) {
      var direct = core.findByFqn(symbolName);
      if (direct.isPresent()) return direct.get();
      // otherwise use the leftmost identifier (Hello in Hello.msg)
      symbolName = symbolName.substring(0, dot);
    }

    FileCtx ctx = ctxByUri.getOrDefault(fileUri, new FileCtx());
    String content = contentByUri.get(fileUri);
    String currentOwnerFqn = ownerAt(fileUri, position, ctx.primaryClassFqn);
    GroovyMemberResolver resolver = memberResolver(core);

    if (content != null && position != null) {
      SymbolInfo receiverResolved =
          resolveQualifiedMember(
              fileUri, content, position, symbolName, ctx, resolver, currentOwnerFqn, core);
      if (receiverResolved != null) {
        return receiverResolved;
      }
      SymbolInfo scopedResolved =
          resolveImplicitMember(fileUri, position, symbolName, resolver, currentOwnerFqn);
      if (scopedResolved != null) {
        return scopedResolved;
      }
    }

    // Alias first: import foo.Bar as B
    String aliasHit = ctx.aliasToFqn.get(symbolName);
    if (aliasHit != null) {
      return core.findByFqn(aliasHit).orElse(null);
    }

    // Fallback: derive package from source text if we didn’t capture it during indexing
    if (ctx.pkg == null || ctx.pkg.isBlank()) {
      if (content != null) {
        var m = PKG_DECL.matcher(content);
        if (m.find()) {
          ctx.pkg = m.group(1);
          ctxByUri.put(fileUri, ctx); // cache for next time
        }
      }
    }

    // 1) Same-package class
    if (!ctx.pkg.isBlank()) {
      String fqn = ctx.pkg + "." + symbolName;
      var hit = core.findByFqn(fqn);
      if (hit.isPresent()) return hit.get();
    }

    // 2) Single-type imports
    for (String imp : ctx.singleImports) {
      if (imp.endsWith("." + symbolName)) {
        var hit = core.findByFqn(imp);
        if (hit.isPresent()) return hit.get();
      }
    }

    // 3) Star imports (explicit + Groovy defaults)
    for (String pkg : union(ctx.starImports, DEFAULT_STAR_IMPORTS)) {
      for (var s : core.allInPackage(pkg)) {
        if (simpleName(s.getFqName()).equals(symbolName)
            && (s.getKind() == SymbolInfo.Kind.CLASS
                || s.getKind() == SymbolInfo.Kind.INTERFACE
                || s.getKind() == SymbolInfo.Kind.ENUM
                || s.getKind() == SymbolInfo.Kind.ANNOTATION)) {
          return s;
        }
      }
    }
    var candidates = new ArrayList<SymbolInfo>();
    for (var s : core.allInPackage(ctx.pkg)) {
      if (simpleName(s.getFqName()).equals(symbolName)) {
        candidates.add(s);
      }
    }
    if (candidates.size() == 1) return candidates.getFirst();

    String lookupName = symbolName;
    if (currentOwnerFqn != null && !currentOwnerFqn.isBlank()) {
      List<SymbolInfo> members =
          resolver.membersAt(fileUri, position, currentOwnerFqn).stream()
              .filter(symbol -> matchesMemberName(symbol, lookupName))
              .toList();
      if (members.size() == 1) {
        return members.getFirst();
      }
    }
    List<SymbolInfo> syntheticMatches =
        core.findBySimpleName(lookupName).stream()
            .filter(symbol -> id().equals(symbol.getLanguageId()))
            .filter(symbol -> symbol.getSyntheticOrigin() != SyntheticOrigin.NONE)
            .filter(
                symbol ->
                    symbol.getLocation() != null && fileUri.equals(symbol.getLocation().getUri()))
            .filter(symbol -> matchesMemberName(symbol, lookupName))
            .toList();
    if (syntheticMatches.size() == 1) {
      return syntheticMatches.getFirst();
    }
    return null;
  }

  @Override
  public List<CompletionItem> completions(String fileUri, Position position, CoreQuery core) {
    String content = contentByUri.get(fileUri);
    if (content == null) return List.of();

    String prefix = completionPrefix(content, position);
    var out = new java.util.LinkedHashMap<String, CompletionItem>(); // stable order, de-duped

    // Build/repair per-file context (pkg/imports/aliases) if needed
    FileCtx ctx = ensureCtx(fileUri, content);

    // ----- dotted prefix: e.g. "thing.Ba"
    int lastDot = prefix.lastIndexOf('.');
    if (lastDot >= 0) {
      String typedPkg = prefix.substring(0, lastDot);
      String simplePrefix = prefix.substring(lastDot + 1);

      boolean starVisibleForTypedPkg = hasStarImportFor(typedPkg, content);

      for (var s : core.allInPackage(typedPkg)) {
        if (!isType(s)) continue;
        String fqn = s.getFqName();
        String simple = simpleName(fqn);
        if (!simple.startsWith(simplePrefix)) continue;

        // visible if same-pkg, single-import, alias, star-import (ctx) OR a raw "import pkg.*" line
        // exists
        boolean visible = isTypeVisibleInFile(fqn, ctx) || starVisibleForTypedPkg;
        boolean needEdit = !visible;

        add(out, s, needEdit ? content : null); // attach auto-import only when NOT visible
      }
      if (!out.isEmpty()) {
        return List.copyOf(out.values());
      }

      collectMembersFromReceiver(
          core,
          fileUri,
          position,
          qualifierOf(prefix),
          simplePrefix,
          ownerAt(fileUri, position, ctx.primaryClassFqn),
          out);
      if (!out.isEmpty()) {
        return List.copyOf(out.values());
      }
    }

    // ----- undotted prefix: e.g. "Ba" -----
    String currentOwnerFqn = ownerAt(fileUri, position, ctx.primaryClassFqn);
    if (currentOwnerFqn != null && !currentOwnerFqn.isBlank()) {
      for (SymbolInfo member : memberResolver(core).membersAt(fileUri, position, currentOwnerFqn)) {
        String name = memberName(member);
        if (member.getKind() == SymbolInfo.Kind.CONSTRUCTOR || !name.startsWith(prefix)) {
          continue;
        }
        addMember(out, member, name);
      }
    }

    // Same package (already visible -> no edits)
    if (ctx.pkg != null && !ctx.pkg.isBlank()) {
      for (var s : core.allInPackage(ctx.pkg)) {
        if (isType(s) && simpleName(s.getFqName()).startsWith(prefix)) {
          add(out, s); // no edits for visible symbols
        }
      }
    }

    // Single-type imports (already visible -> no edits)
    for (String imp : ctx.singleImports) {
      core.findByFqn(imp)
          .ifPresent(
              sym -> {
                if (isType(sym) && simpleName(sym.getFqName()).startsWith(prefix)) {
                  add(out, sym); // no edits
                }
              });
    }

    // Aliases (offer alias label, never edits)
    for (var e : ctx.aliasToFqn.entrySet()) {
      String alias = e.getKey();
      if (!alias.startsWith(prefix)) continue;
      String fqn = e.getValue();
      core.findByFqn(fqn)
          .ifPresent(
              sym -> {
                out.put(
                    "__alias__:" + alias,
                    new CompletionItem(alias, fqn, alias, sym.getLocation(), java.util.List.of()));
              });
    }

    // Star imports (explicit + Groovy defaults) — already visible -> no edits
    for (String p : union(ctx.starImports, DEFAULT_STAR_IMPORTS)) {
      for (var s : core.allInPackage(p)) {
        if (isType(s) && simpleName(s.getFqName()).startsWith(prefix)) {
          add(out, s); // no edits
        }
      }
    }
    return List.copyOf(out.values());
  }

  @Override
  public void forget(String fileUri) {
    ctxByUri.remove(fileUri);
    contentByUri.remove(fileUri);
    clearHierarchy(fileUri);
    classScopesByUri.remove(fileUri);
    scopedMembersByUri.remove(fileUri);
    dynamicMethodTypesByUri.remove(fileUri);
    dynamicPropertyTypesByUri.remove(fileUri);
    strictStaticScopesByUri.remove(fileUri);
    dynamicRelaxedScopesByUri.remove(fileUri);
    missingCoreWarnings.remove(fileUri);
  }

  // ----- internals ------------------------------------------------------------------------------

  private void visitClass(String fileUri, ClassNode cn, SymbolReporter reporter, FileCtx ctx) {
    // Prefer ClassNode’s own package; fallback to ctx
    String clsPkg = cn.getPackageName();
    if ((ctx.pkg == null || ctx.pkg.isBlank()) && clsPkg != null && !clsPkg.isBlank()) {
      ctx.pkg = clsPkg; // keep for resolveSymbol
    }

    // Compute FQN robustly
    String name = cn.getName(); // may already be FQN
    String fqn =
        name.contains(".")
            ? name
            : (ctx.pkg == null || ctx.pkg.isBlank() ? name : ctx.pkg + "." + name);

    boolean isInterface = cn.isInterface();
    boolean isEnum = cn.isEnum();
    boolean isAnno = cn.isAnnotationDefinition();

    reporter.reportClass(fqn, new Location(fileUri, toRange(cn)), isInterface, isEnum, isAnno);
    recordTypeHierarchy(fileUri, fqn, cn, ctx);
    if (ctx.primaryClassFqn.isBlank()) {
      ctx.primaryClassFqn = fqn;
    }
    classScopesByUri
        .computeIfAbsent(fileUri, ignored -> new ArrayList<>())
        .add(new ClassScope(fqn, toRange(cn)));
    recordStrictStaticScopes(fileUri, cn);
    recordDynamicRelaxedScopes(fileUri, cn);

    // Methods
    for (MethodNode mn : cn.getMethods()) {
      if (mn.getDeclaringClass() != cn) continue;
      recordStrictStaticScope(fileUri, mn);
      recordDynamicRelaxedScope(fileUri, mn);
      reporter.reportMethod(
          fqn,
          mn.getName(),
          methodSig(mn, ctx),
          new Location(fileUri, toRange(mn)),
          modifiers(mn.getModifiers()));
    }
    for (ConstructorNode constructor : cn.getDeclaredConstructors()) {
      if (constructor.getDeclaringClass() != cn) continue;
      recordDynamicRelaxedScope(fileUri, constructor);
      reporter.reportConstructor(
          fqn,
          constructorSig(constructor, ctx),
          new Location(fileUri, toRange(constructor)),
          modifiers(constructor.getModifiers()));
    }
    // Fields
    for (FieldNode fn : cn.getFields()) {
      if (fn.getDeclaringClass() != cn) continue;
      reporter.reportField(
          fqn,
          fn.getName(),
          typeOf(fn.getType(), ctx),
          new Location(fileUri, toRange(fn)),
          modifiers(fn.getModifiers()));
    }
    // Groovy properties as fields
    for (PropertyNode pn : cn.getProperties()) {
      reporter.reportField(
          fqn,
          pn.getName(),
          typeOf(pn.getType(), ctx),
          new Location(fileUri, toRange(pn)),
          modifiers(pn.getModifiers()));
    }

    // Script-scope typed variables behave like fields for completion purposes.
    if (cn.isScript()) {
      new ClassCodeVisitorSupport() {
        @Override
        protected SourceUnit getSourceUnit() {
          return null;
        }

        @Override
        public void visitDeclarationExpression(
            org.codehaus.groovy.ast.expr.DeclarationExpression expression) {
          org.codehaus.groovy.ast.expr.Expression left = expression.getLeftExpression();
          if (left instanceof org.codehaus.groovy.ast.expr.VariableExpression variableExpression) {
            ClassNode originType = variableExpression.getOriginType();
            if (originType != null && !variableExpression.isDynamicTyped()) {
              reporter.reportField(
                  fqn,
                  variableExpression.getName(),
                  typeOf(originType, ctx),
                  new Location(fileUri, toRange(expression)),
                  Set.of());
            }
          }
          super.visitDeclarationExpression(expression);
        }
      }.visitClass(cn);
    }
  }

  private static Range toRange(ASTNode n) {
    int sl = safeZero(n.getLineNumber() - 1);
    int sc = safeZero(n.getColumnNumber() - 1);
    int el = safeZero(n.getLastLineNumber() - 1);
    int ec = safeZero(n.getLastColumnNumber() - 1);
    if (el < sl || (el == sl && ec < sc)) { // fallback if Groovy lacks end pos
      el = sl;
      ec = Math.max(sc + 1, 1);
    }
    return new Range(new Position(sl, sc), new Position(el, ec));
  }

  private static Range fromGroovyPos(int sLine1, int sCol1, int eLine1, int eCol1) {
    int sl = safeZero(sLine1 - 1), sc = safeZero(sCol1 - 1);
    int el = safeZero(eLine1 - 1), ec = safeZero(eCol1 - 1);
    if (el < sl || (el == sl && ec < sc)) {
      el = sl;
      ec = Math.max(sc + 1, 1);
    }
    return new Range(new Position(sl, sc), new Position(el, ec));
  }

  private static int safeZero(int x) {
    return Math.max(x, 0);
  }

  private void hydrateCtxFromModule(
      FileCtx fileCtx, ModuleNode moduleNode, String fileUri, SymbolReporter reporter) {
    if (moduleNode.getPackageName() != null) {
      fileCtx.pkg = moduleNode.getPackageName();
    }
    for (ImportNode imp : moduleNode.getImports()) {
      String cls = imp.getClassName();
      if (cls != null && !cls.isBlank()) {
        fileCtx.singleImports.add(cls);
        String alias = imp.getAlias();
        if (alias != null && !alias.isBlank()) {
          fileCtx.aliasToFqn.put(alias, cls);
        }
      }
    }
    for (ImportNode imp : moduleNode.getStarImports()) {
      String pkg = imp.getPackageName();
      if (pkg != null && !pkg.isBlank()) {
        fileCtx.starImports.add(trimDot(pkg));
      }
    }
    if (!fileCtx.pkg.isBlank()) {
      reporter.reportPackage(
          fileCtx.pkg, new Location(fileUri, new Range(new Position(0, 0), new Position(0, 1))));
    }
  }

  private void applyTransforms(
      String fileUri, ClassNode classNode, SymbolReporter reporter, FileCtx ctx) {
    if (coreQuery == null) {
      warnMissingCore(fileUri, "transform analysis");
      return;
    }
    String ownerFqn = ownerFqn(classNode, ctx);
    TransformContext transformContext =
        new TransformContext(
            fileUri,
            ownerFqn,
            coreQuery,
            type -> typeOf(type, ctx),
            node -> new Location(fileUri, toRange(node)));
    for (SyntheticMemberSpec spec : transformRegistry.analyzeClass(classNode, transformContext)) {
      spec.report(reporter);
    }
  }

  private GroovyMemberResolver memberResolver(CoreQuery core) {
    return new GroovyMemberResolver(
        core,
        this::scopedMembersAt,
        typeFqn -> directSupertypesOf(typeFqn, core),
        this::isDynamicMethodType,
        this::isDynamicPropertyType);
  }

  private List<ScopedSyntheticMember> scopedMembersAt(String fileUri, Position position) {
    if (fileUri == null || position == null) {
      return List.of();
    }
    return scopedMembersByUri.getOrDefault(fileUri, List.of()).stream()
        .filter(member -> member.isVisibleAt(position))
        .toList();
  }

  private boolean isDynamicMethodType(String receiverTypeFqn) {
    return dynamicMethodTypesByUri.values().stream()
        .anyMatch(types -> types.contains(receiverTypeFqn));
  }

  private boolean isDynamicPropertyType(String receiverTypeFqn) {
    return dynamicPropertyTypesByUri.values().stream()
        .anyMatch(types -> types.contains(receiverTypeFqn));
  }

  private static String ownerFqn(ClassNode classNode, FileCtx ctx) {
    String clsPkg = classNode.getPackageName();
    String pkg = (clsPkg == null || clsPkg.isBlank()) ? ctx.pkg : clsPkg;
    String name = classNode.getName();
    return name.contains(".") ? name : (pkg == null || pkg.isBlank() ? name : pkg + "." + name);
  }

  private MethodSignature methodSig(MethodNode mn, FileCtx ctx) {
    return signatureFor(
        mn.getParameters(), typeOf(mn.getReturnType(), ctx), modifiers(mn.getModifiers()), ctx);
  }

  private MethodSignature constructorSig(ConstructorNode constructor, FileCtx ctx) {
    return signatureFor(
        constructor.getParameters(), VoidType.INSTANCE, modifiers(constructor.getModifiers()), ctx);
  }

  private MethodSignature signatureFor(
      Parameter[] parameters, JvmType returnType, Set<String> modifiers, FileCtx ctx) {
    List<JvmType> parameterTypes = new ArrayList<>();
    List<String> parameterNames = new ArrayList<>();
    for (Parameter parameter : parameters) {
      parameterTypes.add(typeOf(parameter.getType(), ctx));
      parameterNames.add(parameter.getName());
    }
    return new MethodSignature(
        parameterTypes, returnType, parameterNames, List.of(), List.of(), modifiers);
  }

  private void analyzeDynamicFeatures(
      String fileUri,
      Collection<ClassNode> classes,
      Collection<ModuleNode> modules,
      SymbolReporter reporter,
      FileCtx ctx) {
    CoreQuery core = coreQuery;
    if (core == null) {
      warnMissingCore(fileUri, "dynamic feature analysis");
      return;
    }
    ArrayList<ScopedSyntheticMember> scoped = new ArrayList<>();
    for (ClassNode classNode : classes) {
      String ownerFqn = ownerFqn(classNode, ctx);
      detectDynamicFlags(fileUri, classNode, ownerFqn);
      reportMixinAnnotations(fileUri, classNode, ownerFqn, reporter, ctx, core);
      new ClassCodeVisitorSupport() {
        @Override
        protected SourceUnit getSourceUnit() {
          return null;
        }

        @Override
        public void visitBinaryExpression(BinaryExpression expression) {
          analyzeMetaClassAssignment(fileUri, expression, reporter, ctx);
          super.visitBinaryExpression(expression);
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
          analyzeCategoryUse(fileUri, call, scoped, ctx, core);
          analyzeMixinCall(fileUri, call, reporter, ctx, core);
          super.visitMethodCallExpression(call);
        }
      }.visitClass(classNode);
    }
    for (ModuleNode module : modules) {
      if (module.getStatementBlock() != null) {
        new ClassCodeVisitorSupport() {
          @Override
          protected SourceUnit getSourceUnit() {
            return null;
          }

          @Override
          public void visitBinaryExpression(BinaryExpression expression) {
            analyzeMetaClassAssignment(fileUri, expression, reporter, ctx);
            super.visitBinaryExpression(expression);
          }

          @Override
          public void visitMethodCallExpression(MethodCallExpression call) {
            analyzeCategoryUse(fileUri, call, scoped, ctx, core);
            analyzeMixinCall(fileUri, call, reporter, ctx, core);
            super.visitMethodCallExpression(call);
          }
        }.visitBlockStatement(module.getStatementBlock());
      }
    }
    if (!scoped.isEmpty()) {
      scopedMembersByUri.put(fileUri, List.copyOf(scoped));
    }
  }

  private void analyzeMetaClassAssignment(
      String fileUri, BinaryExpression expression, SymbolReporter reporter, FileCtx ctx) {
    if (!"=".equals(expression.getOperation().getText())) {
      return;
    }
    MetaClassTarget target = metaClassTarget(expression.getLeftExpression());
    if (target == null) {
      return;
    }
    String ownerFqn = resolveTypeName(target.ownerFqn(), ctx);
    Location location = new Location(fileUri, toRange(expression));
    if (expression.getRightExpression() instanceof ClosureExpression closure) {
      MethodSignature signature = closureSignature(closure, target.staticMember(), ctx);
      reporter.reportMethod(
          ownerFqn,
          target.memberName(),
          signature,
          location,
          target.staticMember() ? Set.of("public", "static") : Set.of("public"),
          SyntheticOrigin.METACLASS,
          InferenceConfidence.HIGH);
      return;
    }
    reporter.reportField(
        ownerFqn,
        target.memberName(),
        expressionType(expression.getRightExpression(), ownerFqn, ctx),
        location,
        target.staticMember() ? Set.of("public", "static") : Set.of("public"),
        SyntheticOrigin.METACLASS,
        InferenceConfidence.HIGH);
  }

  private void analyzeCategoryUse(
      String fileUri,
      MethodCallExpression call,
      List<ScopedSyntheticMember> scoped,
      FileCtx ctx,
      CoreQuery core) {
    if (!"use".equals(call.getMethodAsString())) {
      return;
    }
    List<Expression> expressions;
    if (call.getArguments() instanceof ArgumentListExpression arguments) {
      expressions = arguments.getExpressions();
    } else if (call.getArguments() instanceof TupleExpression tupleExpression) {
      expressions = tupleExpression.getExpressions();
    } else {
      expressions = List.of();
    }
    ClosureExpression closure = null;
    if (!expressions.isEmpty()
        && expressions.getLast() instanceof ClosureExpression closureExpression) {
      closure = closureExpression;
      expressions = expressions.subList(0, expressions.size() - 1);
    }
    if (expressions.isEmpty() || closure == null) {
      return;
    }
    for (Expression categoryExpression : expressions) {
      String categoryName = classNameExpression(categoryExpression);
      if (categoryName == null) {
        continue;
      }
      String categoryFqn = resolveTypeName(categoryName, ctx);
      for (SymbolInfo symbol : core.membersOf(categoryFqn)) {
        if (symbol.getKind() != SymbolInfo.Kind.METHOD || symbol.getMethodSignature() == null) {
          continue;
        }
        if (!symbol.getModifiers().contains("static")) {
          continue;
        }
        MethodSignature signature = symbol.getMethodSignature();
        if (signature.parameterTypes().isEmpty()) {
          continue;
        }
        JvmType receiverType = signature.parameterTypes().getFirst();
        if (!(receiverType instanceof ClassType classType)) {
          continue;
        }
        MethodSignature projected = projectCategorySignature(signature);
        scoped.add(
            new ScopedSyntheticMember(
                classType.fqName(),
                toRange(closure),
                new SymbolInfo(
                    "groovy",
                    SymbolInfo.Kind.METHOD,
                    classType.fqName()
                        + "#"
                        + memberName(symbol)
                        + JvmTypes.toLegacyMethodSignature(projected),
                    classType.fqName(),
                    symbol.getLocation(),
                    JvmTypes.toLegacyMethodSignature(projected),
                    projected.modifiers(),
                    projected.typeParameters(),
                    null,
                    projected,
                    SyntheticOrigin.CATEGORY,
                    InferenceConfidence.HIGH)));
      }
    }
  }

  private void analyzeMixinCall(
      String fileUri,
      MethodCallExpression call,
      SymbolReporter reporter,
      FileCtx ctx,
      CoreQuery core) {
    if (!"mixin".equals(call.getMethodAsString())
        || !(call.getObjectExpression() instanceof ClassExpression targetClass)) {
      return;
    }
    if (!(call.getArguments() instanceof ArgumentListExpression arguments)) {
      return;
    }
    String targetFqn = resolveTypeName(targetClass.getType().getName(), ctx);
    for (Expression expression : arguments.getExpressions()) {
      if (expression instanceof ClassExpression mixinClass) {
        reportMixedMembers(
            targetFqn,
            resolveTypeName(mixinClass.getType().getName(), ctx),
            new Location(fileUri, toRange(call)),
            reporter,
            core,
            SyntheticOrigin.MIXIN,
            InferenceConfidence.HIGH);
      }
    }
  }

  private void reportMixinAnnotations(
      String fileUri,
      ClassNode classNode,
      String ownerFqn,
      SymbolReporter reporter,
      FileCtx ctx,
      CoreQuery core) {
    for (AnnotationNode annotation : classNode.getAnnotations()) {
      if (!matchesAnnotation(annotation, GroovyAnnotations.MIXIN)) {
        continue;
      }
      Expression value = annotation.getMember("value");
      for (String mixinName : classNames(value)) {
        reportMixedMembers(
            ownerFqn,
            resolveTypeName(mixinName, ctx),
            new Location(fileUri, toRange(annotation)),
            reporter,
            core,
            SyntheticOrigin.MIXIN,
            InferenceConfidence.DETERMINISTIC);
      }
    }
  }

  private void reportMixedMembers(
      String ownerFqn,
      String mixinFqn,
      Location location,
      SymbolReporter reporter,
      CoreQuery core,
      SyntheticOrigin origin,
      InferenceConfidence confidence) {
    for (SymbolInfo symbol : core.membersOf(mixinFqn)) {
      if (symbol.getModifiers().contains("private")
          || symbol.getKind() == SymbolInfo.Kind.CONSTRUCTOR) {
        continue;
      }
      if (symbol.getKind() == SymbolInfo.Kind.METHOD && symbol.getMethodSignature() != null) {
        reporter.reportMethod(
            ownerFqn,
            memberName(symbol),
            symbol.getMethodSignature(),
            location,
            withoutStatic(symbol.getModifiers()),
            origin,
            confidence);
      } else if (symbol.getKind() == SymbolInfo.Kind.FIELD && symbol.getResolvedType() != null) {
        reporter.reportField(
            ownerFqn,
            memberName(symbol),
            symbol.getResolvedType(),
            location,
            withoutStatic(symbol.getModifiers()),
            origin,
            confidence);
      }
    }
  }

  private void detectDynamicFlags(String fileUri, ClassNode classNode, String ownerFqn) {
    for (MethodNode method : classNode.getMethods()) {
      if ("methodMissing".equals(method.getName())) {
        dynamicMethodTypesByUri
            .computeIfAbsent(fileUri, ignored -> ConcurrentHashMap.newKeySet())
            .add(ownerFqn);
      }
      if ("propertyMissing".equals(method.getName())) {
        dynamicPropertyTypesByUri
            .computeIfAbsent(fileUri, ignored -> ConcurrentHashMap.newKeySet())
            .add(ownerFqn);
      }
    }
  }

  private void runSemanticDiagnostics(
      String fileUri, Collection<ClassNode> classes, List<Diagnostic> diagnostics, FileCtx ctx) {
    CoreQuery core = coreQuery;
    if (core == null) {
      warnMissingCore(fileUri, "semantic diagnostics");
      return;
    }
    GroovyMemberResolver resolver = memberResolver(core);
    for (ClassNode classNode : classes) {
      String ownerFqn = ownerFqn(classNode, ctx);
      new ClassCodeVisitorSupport() {
        @Override
        protected SourceUnit getSourceUnit() {
          return null;
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
          if (isStrictStaticAt(fileUri, toRange(call).start)) {
            String receiverType = receiverTypeFor(call, ownerFqn, ctx, resolver, fileUri);
            String methodName = call.getMethodAsString();
            if (methodName != null
                && receiverType != null
                && !resolver.isDynamicMethodType(receiverType)) {
              boolean found =
                  resolver.membersAt(fileUri, toRange(call).start, receiverType).stream()
                      .anyMatch(
                          symbol ->
                              symbol.getKind() == SymbolInfo.Kind.METHOD
                                  && methodName.equals(memberName(symbol)));
              if (!found) {
                diagnostics.add(
                    new Diagnostic(
                        toRange(call),
                        "Unknown method '" + methodName + "' on " + receiverType,
                        Diagnostic.Severity.ERROR,
                        id(),
                        "undefined-method"));
              }
            }
          }
          super.visitMethodCallExpression(call);
        }

        @Override
        public void visitPropertyExpression(PropertyExpression expression) {
          if (isStrictStaticAt(fileUri, toRange(expression).start)) {
            String receiverType = receiverTypeFor(expression, ownerFqn, ctx, resolver, fileUri);
            String propertyName = expression.getPropertyAsString();
            if (propertyName != null
                && receiverType != null
                && !resolver.isDynamicPropertyType(receiverType)) {
              boolean found =
                  resolver.membersAt(fileUri, toRange(expression).start, receiverType).stream()
                      .anyMatch(symbol -> isPropertySymbol(symbol, propertyName));
              if (!found) {
                diagnostics.add(
                    new Diagnostic(
                        toRange(expression),
                        "Unknown property '" + propertyName + "' on " + receiverType,
                        Diagnostic.Severity.ERROR,
                        id(),
                        "undefined-property"));
              }
            }
          }
          super.visitPropertyExpression(expression);
        }
      }.visitClass(classNode);
    }
  }

  private String receiverTypeFor(
      MethodCallExpression call,
      String ownerFqn,
      FileCtx ctx,
      GroovyMemberResolver resolver,
      String fileUri) {
    if (call.isImplicitThis()) {
      return ownerFqn;
    }
    return expressionTypeFqn(
        call.getObjectExpression(), ownerFqn, ctx, resolver, fileUri, toRange(call).start);
  }

  private String receiverTypeFor(
      PropertyExpression expression,
      String ownerFqn,
      FileCtx ctx,
      GroovyMemberResolver resolver,
      String fileUri) {
    return expressionTypeFqn(
        expression.getObjectExpression(),
        ownerFqn,
        ctx,
        resolver,
        fileUri,
        toRange(expression).start);
  }

  private String expressionTypeFqn(
      Expression expression,
      String ownerFqn,
      FileCtx ctx,
      GroovyMemberResolver resolver,
      String fileUri,
      Position position) {
    if (expression == null) {
      return null;
    }
    if (expression instanceof VariableExpression variable) {
      if ("this".equals(variable.getName())) {
        return ownerFqn;
      }
      if (!variable.isDynamicTyped()) {
        JvmType typed = typeOf(variable.getOriginType(), ctx);
        if (typed instanceof ClassType classType) {
          return classType.fqName();
        }
      }
      for (SymbolInfo symbol : resolver.membersAt(fileUri, position, ownerFqn)) {
        if (symbol.getKind() == SymbolInfo.Kind.FIELD
            && variable.getName().equals(memberName(symbol))
            && symbol.getResolvedType() instanceof ClassType classType) {
          return classType.fqName();
        }
      }
      return null;
    }
    if (expression instanceof ClassExpression classExpression) {
      return resolveTypeName(classExpression.getType().getName(), ctx);
    }
    if (expression instanceof PropertyExpression propertyExpression) {
      String receiverType =
          expressionTypeFqn(
              propertyExpression.getObjectExpression(), ownerFqn, ctx, resolver, fileUri, position);
      if (receiverType == null) {
        return null;
      }
      String propertyName = propertyExpression.getPropertyAsString();
      for (SymbolInfo symbol : resolver.membersAt(fileUri, position, receiverType)) {
        if (propertyName != null
            && propertyName.equals(memberName(symbol))
            && symbol.getResolvedType() instanceof ClassType classType) {
          return classType.fqName();
        }
      }
      return null;
    }
    if (expression instanceof MethodCallExpression callExpression) {
      String receiverType =
          callExpression.isImplicitThis()
              ? ownerFqn
              : expressionTypeFqn(
                  callExpression.getObjectExpression(), ownerFqn, ctx, resolver, fileUri, position);
      if (receiverType == null) {
        return null;
      }
      String methodName = callExpression.getMethodAsString();
      for (SymbolInfo symbol : resolver.membersAt(fileUri, position, receiverType)) {
        if (methodName != null
            && methodName.equals(memberName(symbol))
            && symbol.getMethodSignature() != null
            && symbol.getMethodSignature().returnType() instanceof ClassType classType) {
          return classType.fqName();
        }
      }
      return null;
    }
    ClassNode type = expression.getType();
    if (type == null || type == ClassHelper.DYNAMIC_TYPE) {
      return null;
    }
    JvmType resolved = typeOf(type, ctx);
    return resolved instanceof ClassType classType ? classType.fqName() : null;
  }

  private void recordStrictStaticScopes(String fileUri, ClassNode classNode) {
    recordStrictStaticScope(fileUri, classNode);
  }

  private void recordDynamicRelaxedScopes(String fileUri, ClassNode classNode) {
    recordDynamicRelaxedScope(fileUri, classNode);
  }

  private void recordStrictStaticScope(String fileUri, ASTNode node) {
    if (node instanceof AnnotatedNode annotatedNode && hasStrictStaticAnnotation(annotatedNode)) {
      strictStaticScopesByUri
          .computeIfAbsent(fileUri, ignored -> new ArrayList<>())
          .add(toRange(node));
    }
  }

  private void recordDynamicRelaxedScope(String fileUri, ASTNode node) {
    if (node instanceof AnnotatedNode annotatedNode
        && hasDynamicRelaxationAnnotation(annotatedNode)) {
      dynamicRelaxedScopesByUri
          .computeIfAbsent(fileUri, ignored -> new ArrayList<>())
          .add(toRange(node));
    }
  }

  private boolean isStrictStaticAt(String fileUri, Position position) {
    if (isDynamicRelaxedAt(fileUri, position)) {
      return false;
    }
    for (Range range : strictStaticScopesByUri.getOrDefault(fileUri, List.of())) {
      if (contains(range, position)) {
        return true;
      }
    }
    return false;
  }

  private boolean isDynamicRelaxedAt(String fileUri, Position position) {
    for (Range range : dynamicRelaxedScopesByUri.getOrDefault(fileUri, List.of())) {
      if (contains(range, position)) {
        return true;
      }
    }
    return false;
  }

  private static boolean contains(Range range, Position position) {
    if (position.line < range.start.line || position.line > range.end.line) {
      return false;
    }
    if (position.line == range.start.line && position.column < range.start.column) {
      return false;
    }
    return position.line != range.end.line || position.column <= range.end.column;
  }

  private static boolean hasStrictStaticAnnotation(AnnotatedNode annotatedNode) {
    return annotatedNode.getAnnotations().stream()
        .anyMatch(
            annotation ->
                matchesAnnotation(annotation, GroovyAnnotations.COMPILE_STATIC)
                    || matchesAnnotation(annotation, GroovyAnnotations.TYPE_CHECKED));
  }

  private static boolean hasDynamicRelaxationAnnotation(AnnotatedNode annotatedNode) {
    return annotatedNode.getAnnotations().stream()
        .anyMatch(
            annotation ->
                matchesAnnotation(annotation, GroovyAnnotations.COMPILE_DYNAMIC)
                    || hasSkippedTypeChecking(annotation));
  }

  private static boolean hasSkippedTypeChecking(AnnotationNode annotation) {
    if (!matchesAnnotation(annotation, GroovyAnnotations.COMPILE_STATIC)
        && !matchesAnnotation(annotation, GroovyAnnotations.TYPE_CHECKED)) {
      return false;
    }
    Expression value = annotation.getMember("value");
    if (value == null) {
      return false;
    }
    String text = value.getText();
    return "SKIP".equals(text) || text.endsWith(".SKIP");
  }

  private MetaClassTarget metaClassTarget(Expression expression) {
    if (!(expression instanceof PropertyExpression property)) {
      return null;
    }
    String memberName = property.getPropertyAsString();
    if (memberName == null || memberName.isBlank()) {
      return null;
    }
    if (property.getObjectExpression() instanceof PropertyExpression metaClass
        && "metaClass".equals(metaClass.getPropertyAsString())
        && classNameExpression(metaClass.getObjectExpression()) != null) {
      return new MetaClassTarget(
          classNameExpression(metaClass.getObjectExpression()), memberName, false);
    }
    if (property.getObjectExpression() instanceof PropertyExpression staticExpr
        && "static".equals(staticExpr.getPropertyAsString())
        && staticExpr.getObjectExpression() instanceof PropertyExpression metaClass
        && "metaClass".equals(metaClass.getPropertyAsString())
        && classNameExpression(metaClass.getObjectExpression()) != null) {
      return new MetaClassTarget(
          classNameExpression(metaClass.getObjectExpression()), memberName, true);
    }
    return null;
  }

  private static String classNameExpression(Expression expression) {
    if (expression instanceof ClassExpression classExpression) {
      return classExpression.getType().getName();
    }
    if (expression instanceof VariableExpression variableExpression) {
      return variableExpression.getName();
    }
    return null;
  }

  private static boolean matchesAnnotation(AnnotationNode annotation, String fqName) {
    if (annotation == null || annotation.getClassNode() == null) {
      return false;
    }
    String annotationName = annotation.getClassNode().getName();
    return fqName.equals(annotationName)
        || fqName.endsWith("." + annotation.getClassNode().getNameWithoutPackage());
  }

  private void warnMissingCore(String fileUri, String feature) {
    if (missingCoreWarnings.add(fileUri)) {
      LOG.warning(
          "Groovy plugin is not configured with CoreQuery; "
              + feature
              + " is disabled for "
              + fileUri);
    }
  }

  private static List<String> classNames(Expression expression) {
    if (expression == null) {
      return List.of();
    }
    String className = classNameExpression(expression);
    if (className != null) {
      return List.of(className);
    }
    if (expression instanceof TupleExpression tupleExpression) {
      ArrayList<String> names = new ArrayList<>();
      for (Expression child : tupleExpression.getExpressions()) {
        names.addAll(classNames(child));
      }
      return List.copyOf(names);
    }
    if (expression instanceof ListExpression listExpression) {
      ArrayList<String> names = new ArrayList<>();
      for (Expression child : listExpression.getExpressions()) {
        names.addAll(classNames(child));
      }
      return List.copyOf(names);
    }
    return List.of();
  }

  private MethodSignature closureSignature(
      ClosureExpression closure, boolean staticMember, FileCtx ctx) {
    List<JvmType> parameterTypes = new ArrayList<>();
    List<String> parameterNames = new ArrayList<>();
    Parameter[] parameters = closure.getParameters();
    if (parameters != null) {
      for (Parameter parameter : parameters) {
        parameterTypes.add(typeOf(parameter.getType(), ctx));
        parameterNames.add(parameter.getName());
      }
    }
    return new MethodSignature(
        parameterTypes,
        DynamicType.INSTANCE,
        parameterNames,
        List.of(),
        List.of(),
        staticMember ? Set.of("public", "static") : Set.of("public"));
  }

  private JvmType expressionType(Expression expression, String ownerFqn, FileCtx ctx) {
    if (expression == null) {
      return DynamicType.INSTANCE;
    }
    if (expression instanceof ConstructorCallExpression constructorCall) {
      return typeOf(constructorCall.getType(), ctx);
    }
    if (expression instanceof ClassExpression classExpression) {
      return typeOf(classExpression.getType(), ctx);
    }
    if (expression instanceof ConstantExpression constant) {
      ClassNode type = constant.getType();
      if (type != null && type != ClassHelper.DYNAMIC_TYPE) {
        return typeOf(type, ctx);
      }
    }
    if (expression instanceof GStringExpression) {
      return new ClassType("groovy.lang.GString", List.of());
    }
    if (expression instanceof ListExpression) {
      return new ClassType("java.util.List", List.of());
    }
    if (expression instanceof MapExpression) {
      return new ClassType("java.util.Map", List.of());
    }
    ClassNode type = expression.getType();
    if (type == null || type == ClassHelper.DYNAMIC_TYPE) {
      return DynamicType.INSTANCE;
    }
    return typeOf(type, ctx);
  }

  private static MethodSignature projectCategorySignature(MethodSignature signature) {
    List<JvmType> parameterTypes =
        signature.parameterTypes().subList(1, signature.parameterTypes().size());
    List<String> parameterNames =
        signature.parameterNames().size() <= 1
            ? List.of()
            : signature.parameterNames().subList(1, signature.parameterNames().size());
    List<JvmType> throwsTypes = signature.throwsTypes();
    List<String> declarations = signature.typeParameters();
    LinkedHashSet<String> requiredTypeParameters = new LinkedHashSet<>();
    for (String declaration : declarations) {
      if (referencesTypeParameter(declaration, parameterTypes, signature.returnType(), throwsTypes)) {
        collectTypeParameterDependencies(declaration, declarations, requiredTypeParameters);
      }
    }
    List<String> typeParameters =
        declarations.stream().filter(requiredTypeParameters::contains).toList();
    return new MethodSignature(
        parameterTypes,
        signature.returnType(),
        parameterNames,
        typeParameters,
        throwsTypes,
        withoutStatic(signature.modifiers()));
  }

  private static boolean referencesTypeParameter(
      String declaration,
      List<JvmType> parameterTypes,
      JvmType returnType,
      List<JvmType> throwsTypes) {
    String name = typeParameterName(declaration);
    if (name == null) {
      return false;
    }
    if (returnType.displayName().contains(name)) {
      return true;
    }
    for (JvmType parameterType : parameterTypes) {
      if (parameterType.displayName().contains(name)) {
        return true;
      }
    }
    for (JvmType throwsType : throwsTypes) {
      if (throwsType.displayName().contains(name)) {
        return true;
      }
    }
    return false;
  }

  private static void collectTypeParameterDependencies(
      String declaration, List<String> declarations, Set<String> required) {
    String name = typeParameterName(declaration);
    if (name == null || !required.add(declaration)) {
      return;
    }
    for (String candidate : declarations) {
      if (candidate.equals(declaration)) {
        continue;
      }
      String candidateName = typeParameterName(candidate);
      if (candidateName != null && declaration.contains(candidateName)) {
        collectTypeParameterDependencies(candidate, declarations, required);
      }
    }
  }

  private static String typeParameterName(String declaration) {
    if (declaration == null) {
      return null;
    }
    int space = declaration.indexOf(' ');
    return (space < 0 ? declaration : declaration.substring(0, space)).trim();
  }

  private static Set<String> withoutStatic(Set<String> modifiers) {
    LinkedHashSet<String> out = new LinkedHashSet<>(modifiers == null ? Set.of() : modifiers);
    out.remove("static");
    return Set.copyOf(out);
  }

  private record MetaClassTarget(String ownerFqn, String memberName, boolean staticMember) {}

  private static String typeName(ClassNode t) {
    return t == null ? "java.lang.Object" : t.getName();
  }

  private JvmType typeOf(ClassNode node, FileCtx ctx) {
    if (node == null) {
      return DynamicType.INSTANCE;
    }
    if (node.isArray()) {
      return new ArrayType(typeOf(node.getComponentType(), ctx));
    }
    if (node == ClassHelper.DYNAMIC_TYPE) {
      return DynamicType.INSTANCE;
    }
    String name = node.getName();
    if (JvmTypes.isPrimitive(name)) {
      return new PrimitiveType(name);
    }
    List<JvmType> typeArguments =
        node.getGenericsTypes() == null
            ? List.of()
            : Arrays.stream(node.getGenericsTypes())
                .map(
                    genericsType -> {
                      if (genericsType.isWildcard()) {
                        return DynamicType.INSTANCE;
                      }
                      ClassNode type = genericsType.getType();
                      return type == null ? DynamicType.INSTANCE : typeOf(type, ctx);
                    })
                .toList();
    return new ClassType(resolveTypeName(name, ctx), typeArguments);
  }

  private String resolveTypeName(String name, FileCtx ctx) {
    if (name == null || name.isBlank() || name.contains(".")) {
      return name;
    }
    String aliasTarget = ctx.aliasToFqn.get(name);
    if (aliasTarget != null) {
      return aliasTarget;
    }
    TypeResolver resolver = typeResolver;
    List<String> visibleImports =
        new ArrayList<>(
            ctx.singleImports.size()
                + ctx.starImports.size()
                + DEFAULT_SINGLE_IMPORTS.size()
                + DEFAULT_STAR_IMPORTS.size());
    visibleImports.addAll(ctx.singleImports);
    visibleImports.addAll(DEFAULT_SINGLE_IMPORTS);
    for (String pkg : ctx.starImports) {
      visibleImports.add(normPkg(pkg) + ".*");
    }
    for (String pkg : DEFAULT_STAR_IMPORTS) {
      visibleImports.add(pkg + ".*");
    }
    if (resolver == null) {
      return fallbackResolveTypeName(name, ctx, visibleImports);
    }
    String resolved = resolver.resolveClassName(name, ctx.pkg, visibleImports);
    if (!Objects.equals(resolved, name)) {
      return resolved;
    }
    return (ctx.pkg == null || ctx.pkg.isBlank()) ? name : ctx.pkg + "." + name;
  }

  private static String fallbackResolveTypeName(
      String name, FileCtx ctx, List<String> visibleImports) {
    for (String imported : visibleImports) {
      if (!imported.endsWith(".*") && imported.endsWith("." + name)) {
        return imported;
      }
    }
    String samePackage = (ctx.pkg == null || ctx.pkg.isBlank()) ? name : ctx.pkg + "." + name;
    if (isKnownRuntimeType(samePackage)) {
      return samePackage;
    }
    for (String imported : visibleImports) {
      if (imported.endsWith(".*")) {
        String candidate = imported.substring(0, imported.length() - 2) + "." + name;
        if (isKnownRuntimeType(candidate)) {
          return candidate;
        }
      }
    }
    return samePackage;
  }

  private static boolean isKnownRuntimeType(String fqn) {
    try {
      Class.forName(fqn, false, GroovyPlugin.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError ignored) {
      return false;
    }
  }

  private static String simpleName(String fqn) {
    int i = fqn.lastIndexOf('.');
    return i < 0 ? fqn : fqn.substring(i + 1);
  }

  private static List<String> union(List<String> a, List<String> b) {
    ArrayList<String> out = new ArrayList<>(a.size() + b.size());
    out.addAll(a);
    out.addAll(b);
    return out;
  }

  private static String trimDot(String pkg) {
    return pkg.endsWith(".") ? pkg.substring(0, pkg.length() - 1) : pkg;
  }

  private static boolean isType(SymbolInfo s) {
    return switch (s.getKind()) {
      case CLASS, INTERFACE, ENUM, ANNOTATION -> true;
      default -> false;
    };
  }

  private static void add(java.util.Map<String, CompletionItem> out, SymbolInfo s, String content) {
    String fqn = s.getFqName();
    if (out.containsKey(fqn)) return;
    String simple = simpleName(fqn);
    var edits =
        (content == null)
            ? java.util.List.<se.alipsa.jvmpls.core.model.TextEdit>of()
            : maybeImportEdit(content, fqn);
    out.put(
        fqn,
        new CompletionItem(
            simple,
            fqn,
            simple,
            s.getLocation(),
            edits,
            "",
            s.getSyntheticOrigin(),
            s.getInferenceConfidence()));
  }

  private static void addMember(
      java.util.Map<String, CompletionItem> out, SymbolInfo s, String label) {
    String key = memberCompletionKey(s, label);
    if (out.containsKey(key)) return;
    String typeDetail =
        s.getResolvedType() != null
            ? s.getResolvedType().displayName()
            : s.getMethodSignature() != null
                ? s.getMethodSignature().returnType().displayName()
                : s.getSignature();
    String detail =
        s.getMethodSignature() != null
            ? s.getContainerFqName() + JvmTypes.toLegacyMethodSignature(s.getMethodSignature())
            : s.getContainerFqName();
    out.put(
        key,
        new CompletionItem(
            label,
            detail,
            label,
            s.getLocation(),
            List.of(),
            typeDetail,
            s.getSyntheticOrigin(),
            s.getInferenceConfidence()));
  }

  // Backward-compat for places that don't care about edits
  private static void add(java.util.Map<String, CompletionItem> out, SymbolInfo s) {
    add(out, s, null);
  }

  private static java.util.List<TextEdit> maybeImportEdit(String content, String fqn) {
    // Resolve current package
    String pkg = null;
    var pm = PKG_DECL.matcher(content);
    if (pm.find()) pkg = pm.group(1);

    String simple = simpleName(fqn);
    String owner = fqn.substring(0, fqn.length() - simple.length() - 1);

    // No import needed in same package, default star imports, or Groovy's default single imports
    if (owner.equals(pkg)
        || DEFAULT_STAR_IMPORTS.contains(owner)
        || DEFAULT_SINGLE_IMPORTS.contains(fqn)) {
      return List.of();
    }

    // Already imported explicitly or via (non-static) star?
    var IMPORT_WITH_ALIAS =
        java.util.regex.Pattern.compile(
            "(?m)^\\s*import(?:\\s+(static))?\\s+([\\w.]+)(?:\\s+as\\s+(\\w+))?\\s*$");
    var im = IMPORT_WITH_ALIAS.matcher(content);
    while (im.find()) {
      String isStatic = im.group(1);
      String imp = im.group(2);
      if (isStatic != null) continue; // ignore static imports here
      if (imp.equals(fqn) || imp.equals(owner + ".*")) return java.util.List.of();
    }

    // Compute insertion point: after last (non-static) import, else after package, else at top
    int insertAt = -1;
    int lastImportEnd = -1;
    im.reset();
    while (im.find()) {
      if (im.group(1) == null) lastImportEnd = im.end(); // only non-static imports
    }
    if (lastImportEnd >= 0) insertAt = lastImportEnd;
    else if (pm.reset().find()) insertAt = pm.end();
    else insertAt = 0;

    // Convert char offset to Position
    int line = 0, col = 0;
    for (int i = 0; i < insertAt; i++) {
      char c = content.charAt(i);
      if (c == '\n') {
        line++;
        col = 0;
      } else {
        col++;
      }
    }

    // Build edit text (Groovy has no semicolons)
    String prefixNL =
        (insertAt == 0 || content.charAt(Math.max(0, insertAt - 1)) == '\n') ? "" : "\n";
    String newText = prefixNL + "import " + fqn + "\n";

    var range = new Range(new Position(line, col), new Position(line, col));
    var edit = new TextEdit(range, newText);
    return List.of(edit);
  }

  private static void collectTypesFromPackage(
      CoreQuery core,
      String pkg,
      String simplePrefix,
      String content,
      java.util.Map<String, CompletionItem> out) {
    if (pkg == null || pkg.isBlank()) return;
    for (var s : core.allInPackage(pkg)) {
      if (isType(s) && simpleName(s.getFqName()).startsWith(simplePrefix)) {
        add(out, s, content);
      }
    }
  }

  private void collectMembersFromReceiver(
      CoreQuery core,
      String fileUri,
      Position position,
      String receiver,
      String memberPrefix,
      String currentOwnerFqn,
      java.util.Map<String, CompletionItem> out) {
    if (currentOwnerFqn == null || currentOwnerFqn.isBlank()) {
      return;
    }
    GroovyMemberResolver resolver = memberResolver(core);
    for (SymbolInfo symbol : resolver.membersAt(fileUri, position, currentOwnerFqn)) {
      if (symbol.getKind() != SymbolInfo.Kind.FIELD) {
        continue;
      }
      if (!receiver.equals(memberName(symbol))) {
        continue;
      }
      if (symbol.getResolvedType() instanceof ClassType classType) {
        for (SymbolInfo member : resolver.membersAt(fileUri, position, classType.fqName())) {
          String name = memberName(member);
          if (name.startsWith(memberPrefix)
              && isVisible(member, currentOwnerFqn, classType.fqName(), core)) {
            addMember(out, member, name);
          }
        }
      }
      return;
    }
  }

  private static String completionPrefix(String content, Position pos) {
    int offset = se.alipsa.jvmpls.core.TokenUtil.positionToOffset(content, pos.line, pos.column);
    int i = Math.max(0, Math.min(offset, content.length()));
    int s = i;
    while (s > 0) {
      char c = content.charAt(s - 1);
      if (!(Character.isAlphabetic(c) || Character.isDigit(c) || c == '_' || c == '$' || c == '.'))
        break;
      s--;
    }
    return content.substring(s, i);
  }

  private static String qualifierOf(String prefix) {
    int lastDot = prefix.lastIndexOf('.');
    return lastDot < 0 ? prefix : prefix.substring(0, lastDot);
  }

  private static String ownerPkg(String fqn) {
    int i = fqn.lastIndexOf('.');
    return i < 0 ? "" : fqn.substring(0, i);
  }

  // Normalize: strip trailing '.' if present
  private static String normPkg(String p) {
    if (p == null) return "";
    int n = p.length();
    if (n > 0 && p.charAt(n - 1) == '.') return p.substring(0, n - 1);
    return p;
  }

  private static String ownerOf(String fqn) {
    int i = fqn.lastIndexOf('.');
    return i < 0 ? "" : fqn.substring(0, i);
  }

  private static String memberName(SymbolInfo symbol) {
    String fqn = symbol.getFqName();
    int hash = fqn.lastIndexOf('#');
    if (hash >= 0) {
      String suffix = fqn.substring(hash + 1);
      int open = suffix.indexOf('(');
      return open >= 0 ? suffix.substring(0, open) : suffix;
    }
    return simpleName(fqn);
  }

  private SymbolInfo resolveQualifiedMember(
      String fileUri,
      String content,
      Position position,
      String symbolName,
      FileCtx ctx,
      GroovyMemberResolver resolver,
      String currentOwnerFqn,
      CoreQuery core) {
    if (currentOwnerFqn == null || currentOwnerFqn.isBlank()) {
      return null;
    }
    String qualifier = null;
    String dottedPrefix = completionPrefix(content, position);
    int lastDot = dottedPrefix.lastIndexOf('.');
    if (lastDot >= 0) {
      String suffix = dottedPrefix.substring(lastDot + 1);
      if (symbolName.equals(suffix)) {
        qualifier = dottedPrefix.substring(0, lastDot);
      }
    }
    if (qualifier == null) {
      qualifier = qualifierBeforeToken(content, position);
    }
    if (qualifier == null || qualifier.isBlank()) {
      return null;
    }
    String receiverType =
        receiverTypeForQualifier(
            qualifier, currentOwnerFqn, ctx, resolver, fileUri, position, core);
    if (receiverType == null) {
      return null;
    }
    List<SymbolInfo> matches =
        resolver.membersAt(fileUri, position, receiverType).stream()
            .filter(symbol -> matchesMemberName(symbol, symbolName))
            .toList();
    if (matches.isEmpty()) {
      matches =
          core.membersOf(receiverType).stream()
              .filter(symbol -> matchesMemberName(symbol, symbolName))
              .toList();
    }
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  private SymbolInfo resolveImplicitMember(
      String fileUri,
      Position position,
      String symbolName,
      GroovyMemberResolver resolver,
      String currentOwnerFqn) {
    if (currentOwnerFqn == null || currentOwnerFqn.isBlank()) {
      return null;
    }
    List<SymbolInfo> matches =
        resolver.membersAt(fileUri, position, currentOwnerFqn).stream()
            .filter(symbol -> matchesMemberName(symbol, symbolName))
            .toList();
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  private String receiverTypeForQualifier(
      String qualifier,
      String ownerFqn,
      FileCtx ctx,
      GroovyMemberResolver resolver,
      String fileUri,
      Position position,
      CoreQuery core) {
    String[] parts = qualifier.split("\\.");
    String currentType = null;
    for (int i = 0; i < parts.length; i++) {
      String part = parts[i];
      if (part.isBlank()) {
        return null;
      }
      if (i == 0) {
        if ("this".equals(part)) {
          currentType = ownerFqn;
          continue;
        }
        String resolvedType = resolveTypeName(part, ctx);
        if (resolvedType != null && core.findByFqn(resolvedType).isPresent()) {
          currentType = resolvedType;
          continue;
        }
        currentType = memberType(ownerFqn, part, resolver, fileUri, position);
        if (currentType == null) {
          currentType = directMemberType(ownerFqn, part, core);
        }
      } else {
        currentType = memberType(currentType, part, resolver, fileUri, position);
        if (currentType == null) {
          return null;
        }
      }
      if (currentType == null) {
        return null;
      }
    }
    return currentType;
  }

  private String memberType(
      String receiverType,
      String memberName,
      GroovyMemberResolver resolver,
      String fileUri,
      Position position) {
    if (receiverType == null) {
      return null;
    }
    for (SymbolInfo symbol : resolver.membersAt(fileUri, position, receiverType)) {
      if (!matchesMemberName(symbol, memberName)) {
        continue;
      }
      if (symbol.getResolvedType() instanceof ClassType classType) {
        return classType.fqName();
      }
      if (symbol.getMethodSignature() != null
          && symbol.getMethodSignature().returnType() instanceof ClassType classType) {
        return classType.fqName();
      }
    }
    return null;
  }

  private static String directMemberType(String receiverType, String memberName, CoreQuery core) {
    if (receiverType == null || core == null) {
      return null;
    }
    for (SymbolInfo symbol : core.membersOf(receiverType)) {
      if (!matchesMemberName(symbol, memberName)) {
        continue;
      }
      if (symbol.getResolvedType() instanceof ClassType classType) {
        return classType.fqName();
      }
      if (symbol.getMethodSignature() != null
          && symbol.getMethodSignature().returnType() instanceof ClassType classType) {
        return classType.fqName();
      }
    }
    return null;
  }

  private static String qualifierBeforeToken(String content, Position position) {
    if (content == null || position == null) {
      return null;
    }
    int offset =
        se.alipsa.jvmpls.core.TokenUtil.positionToOffset(content, position.line, position.column);
    int n = content.length();
    int i = Math.max(0, Math.min(offset, n - 1));
    if (!isWord(content.charAt(i)) && i > 0 && isWord(content.charAt(i - 1))) {
      i--;
    }
    int start = i;
    while (start > 0 && isWord(content.charAt(start - 1))) {
      start--;
    }
    int dot = start - 1;
    while (dot >= 0 && Character.isWhitespace(content.charAt(dot))) {
      dot--;
    }
    if (dot < 0 || content.charAt(dot) != '.') {
      return null;
    }
    int end = dot;
    int qualifierStart = end;
    while (qualifierStart > 0) {
      char c = content.charAt(qualifierStart - 1);
      if (!(isWord(c) || c == '.')) {
        break;
      }
      qualifierStart--;
    }
    return qualifierStart == end ? null : content.substring(qualifierStart, end);
  }

  private static boolean matchesMemberName(SymbolInfo symbol, String lookupName) {
    return lookupName.equals(memberName(symbol)) || isPropertySymbol(symbol, lookupName);
  }

  private static boolean isPropertySymbol(SymbolInfo symbol, String propertyName) {
    if (symbol.getKind() == SymbolInfo.Kind.FIELD) {
      return propertyName.equals(memberName(symbol));
    }
    if (symbol.getKind() != SymbolInfo.Kind.METHOD
        || symbol.getMethodSignature() == null
        || !symbol.getMethodSignature().parameterTypes().isEmpty()) {
      return false;
    }
    String getterProperty = getterPropertyName(memberName(symbol));
    return propertyName.equals(getterProperty);
  }

  private static String getterPropertyName(String methodName) {
    if (methodName.startsWith("get") && methodName.length() > 3) {
      return decapitalize(methodName.substring(3));
    }
    if (methodName.startsWith("is") && methodName.length() > 2) {
      return decapitalize(methodName.substring(2));
    }
    return null;
  }

  private static String decapitalize(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    if (value.length() > 1
        && Character.isUpperCase(value.charAt(1))
        && Character.isUpperCase(value.charAt(0))) {
      return value;
    }
    return Character.toLowerCase(value.charAt(0)) + value.substring(1);
  }

  private static boolean isWord(char c) {
    return Character.isAlphabetic(c) || Character.isDigit(c) || c == '_' || c == '$';
  }

  private static Set<String> modifiers(int flags) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    if (Modifier.isPublic(flags)) out.add("public");
    if (Modifier.isProtected(flags)) out.add("protected");
    if (Modifier.isPrivate(flags)) out.add("private");
    if (!Modifier.isPublic(flags) && !Modifier.isProtected(flags) && !Modifier.isPrivate(flags))
      out.add("package-private");
    if (Modifier.isAbstract(flags)) out.add("abstract");
    if (Modifier.isFinal(flags)) out.add("final");
    if (Modifier.isStatic(flags)) out.add("static");
    return Set.copyOf(out);
  }

  private boolean isVisible(
      SymbolInfo member, String currentOwnerFqn, String receiverType, CoreQuery core) {
    Set<String> modifiers = member.getModifiers();
    if (member.getContainerFqName().equals(currentOwnerFqn)) {
      return true;
    }
    if (modifiers == null) {
      return false;
    }
    if (modifiers.contains("public")) {
      return true;
    }
    if (modifiers.contains("private")) {
      return false;
    }
    String currentPackage = ownerPkg(currentOwnerFqn);
    String ownerPackage = ownerPkg(member.getContainerFqName());
    if (modifiers.contains("package-private")) {
      return Objects.equals(currentPackage, ownerPackage);
    }
    if (modifiers.contains("protected")) {
      return Objects.equals(currentPackage, ownerPackage)
          || (isSubtypeOrSame(currentOwnerFqn, member.getContainerFqName(), core)
              && isSubtypeOrSame(receiverType, currentOwnerFqn, core));
    }
    return false;
  }

  private boolean isSubtypeOrSame(String sourceType, String targetType, CoreQuery core) {
    return Objects.equals(sourceType, targetType)
        || isSubtypeOf(sourceType, targetType, core, new LinkedHashSet<>());
  }

  private boolean isSubtypeOf(
      String currentType, String targetType, CoreQuery core, Set<String> visited) {
    if (currentType == null || currentType.isBlank() || !visited.add(currentType)) {
      return false;
    }
    for (String supertype : directSupertypesOf(currentType, core)) {
      if (targetType.equals(supertype) || isSubtypeOf(supertype, targetType, core, visited)) {
        return true;
      }
    }
    return false;
  }

  private List<String> directSupertypesOf(String typeFqn, CoreQuery core) {
    List<String> local = directSupertypesByType.get(typeFqn);
    if (local != null && !local.isEmpty()) {
      return local;
    }
    return core == null ? List.of() : core.supertypesOf(typeFqn);
  }

  private void recordTypeHierarchy(
      String fileUri, String typeFqn, ClassNode classNode, FileCtx ctx) {
    ArrayList<String> supertypes = new ArrayList<>();
    ClassNode superClass = classNode.getSuperClass();
    if (superClass != null && !"java.lang.Object".equals(superClass.getName())) {
      JvmType extendsType = typeOf(superClass, ctx);
      if (extendsType instanceof ClassType classType) {
        supertypes.add(classType.fqName());
      }
    }
    for (ClassNode interfaceNode : classNode.getInterfaces()) {
      JvmType interfaceType = typeOf(interfaceNode, ctx);
      if (interfaceType instanceof ClassType classType) {
        supertypes.add(classType.fqName());
      }
    }
    directSupertypesByType.put(typeFqn, List.copyOf(new LinkedHashSet<>(supertypes)));
    typesByUri.computeIfAbsent(fileUri, ignored -> ConcurrentHashMap.newKeySet()).add(typeFqn);
  }

  private void clearHierarchy(String fileUri) {
    Set<String> types = typesByUri.remove(fileUri);
    if (types == null) {
      return;
    }
    for (String type : types) {
      directSupertypesByType.remove(type);
    }
  }

  private String ownerAt(String fileUri, Position position, String fallbackOwner) {
    if (position == null) {
      return fallbackOwner;
    }
    ClassScope best = null;
    for (ClassScope scope : classScopesByUri.getOrDefault(fileUri, List.of())) {
      if (!contains(scope.range(), position)) {
        continue;
      }
      if (best == null || span(scope.range()) <= span(best.range())) {
        best = scope;
      }
    }
    return best == null ? fallbackOwner : best.ownerFqn();
  }

  private static int span(Range range) {
    return (range.end.line - range.start.line) * 10_000 + (range.end.column - range.start.column);
  }

  private static String memberCompletionKey(SymbolInfo symbol, String label) {
    return switch (symbol.getKind()) {
      case FIELD -> "FIELD:" + label;
      case METHOD ->
          "METHOD:"
              + label
              + ":"
              + (symbol.getMethodSignature() == null
                  ? symbol.getSignature()
                  : JvmTypes.toLegacyMethodSignature(symbol.getMethodSignature()));
      default -> symbol.getFqName();
    };
  }

  /** Build/augment ctx if parse failed earlier */
  private FileCtx ensureCtx(String fileUri, String content) {
    FileCtx ctx = ctxByUri.get(fileUri);
    if (ctx == null) ctx = new FileCtx();

    hydrateCtxFromSource(ctx, content);

    ctxByUri.put(fileUri, ctx);
    return ctx;
  }

  private static void hydrateCtxFromSource(FileCtx ctx, String content) {
    if (ctx.pkg == null || ctx.pkg.isBlank()) {
      var pm = PKG_DECL.matcher(content);
      if (pm.find()) ctx.pkg = pm.group(1);
    }

    if (ctx.singleImports.isEmpty() && ctx.starImports.isEmpty() && ctx.aliasToFqn.isEmpty()) {
      var IMPORT_WITH_ALIAS =
          java.util.regex.Pattern.compile(
              "(?m)^\\s*import(?:\\s+(static))?\\s+([\\w.]+)(?:\\s+as\\s+(\\w+))?\\s*$");
      var m = IMPORT_WITH_ALIAS.matcher(content);
      while (m.find()) {
        String isStatic = m.group(1);
        String target = m.group(2);
        String alias = m.group(3);
        if (isStatic != null) continue; // static imports are members, not types
        if (STAR_SUFFIX.matcher(target).find()) {
          ctx.starImports.add(
              normPkg(target.substring(0, target.length() - 2))); // "a.b.*" -> "a.b"
        } else {
          ctx.singleImports.add(target);
          if (alias != null && !alias.isBlank()) ctx.aliasToFqn.put(alias, target);
        }
      }
    }
  }

  /**
   * True if fqn already visible in file by package, single import, alias, or star import (incl.
   * Groovy defaults).
   */
  private static boolean isTypeVisibleInFile(String fqn, FileCtx ctx) {
    String owner = normPkg(ownerOf(fqn));
    if (owner.equals(normPkg(ctx.pkg))) return true;
    if (ctx.singleImports.contains(fqn)) return true;
    if (ctx.aliasToFqn.containsValue(fqn)) return true;
    for (String p : ctx.starImports) {
      if (normPkg(p).equals(owner)) return true;
    }
    for (String p : DEFAULT_STAR_IMPORTS) {
      if (normPkg(p).equals(owner)) return true;
    }
    return false;
  }

  /** Extra guard: if parser missed imports, detect a literal `import <pkg>.*` line in the text. */
  private static boolean hasStarImportFor(String pkg, String content) {
    String needle = "import " + pkg + ".*";
    String needleStatic = "import static " + pkg + ".*";
    // crude but effective and whitespace-tolerant enough for tests
    return content.contains(needle) || content.contains(needleStatic);
  }
}
