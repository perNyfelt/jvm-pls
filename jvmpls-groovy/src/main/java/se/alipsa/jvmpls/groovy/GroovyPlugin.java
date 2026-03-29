package se.alipsa.jvmpls.groovy;

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

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.builder.AstBuilder;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class GroovyPlugin implements JvmLangPlugin {

  @Override public String id() { return "groovy"; } 

  @Override public Set<String> fileExtensions() { return Set.of("groovy", "gvy", "gy", "gsh"); }

  private static final Pattern PKG_DECL =
      Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;?\\s*$");
  private static final Pattern STAR_SUFFIX =
      Pattern.compile("\\.\\*");
  // Groovy default single-type imports (no import line should be suggested)
  private static final Set<String> DEFAULT_SINGLE_IMPORTS = Set.of(
      "java.math.BigInteger", "java.math.BigDecimal"
  );

  // Keep per-file context for resolveSymbol (package & imports)
  private static final class FileCtx {
    String pkg = "";
    final List<String> singleImports = new ArrayList<>(); // a.b.C
    final List<String> starImports   = new ArrayList<>(); // a.b (package)
    final Map<String,String> aliasToFqn = new HashMap<>(); // alias -> FQN
    String primaryClassFqn = "";
  }

  private final Map<String, FileCtx> ctxByUri = new ConcurrentHashMap<>();
  private final Map<String, String>  contentByUri = new ConcurrentHashMap<>();
  private final Map<String, List<String>> directSupertypesByType = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> typesByUri = new ConcurrentHashMap<>();
  private volatile TypeResolver typeResolver;

  // Groovy default star imports (visibility without explicit imports)
  private static final List<String> DEFAULT_STAR_IMPORTS = List.of(
      "java.lang", "java.util", "java.io", "java.net", "groovy.lang", "groovy.util"
  );

  @Override
  public void configure(PluginEnvironment env) {
    typeResolver = new TypeResolver(env.core());
  }

  @Override
  public List<Diagnostic> index(String fileUri, String content, SymbolReporter reporter) {
    contentByUri.put(fileUri, content);
    clearHierarchy(fileUri);
    var diags = new ArrayList<Diagnostic>();
    var fileCtx = new FileCtx();
    hydrateCtxFromSource(fileCtx, content);

    try {
      // Parse to at least CONVERSION so ClassNode/MethodNode are populated.
      List<ASTNode> nodes = new AstBuilder().buildFromString(
          CompilePhase.CONVERSION, false, content);

      // Walk ModuleNodes (for package/imports) and ClassNodes (for declarations)
      for (ASTNode n : nodes) {
        if (n instanceof ModuleNode mn) {
          // package
          if (mn.getPackageName() != null) fileCtx.pkg = mn.getPackageName();

          // single imports (import a.b.C or import a.b.C as X)
          for (ImportNode imp : mn.getImports()) {
            String cls = imp.getClassName();
            if (cls != null && !cls.isBlank()) {
              fileCtx.singleImports.add(cls);
              String alias = imp.getAlias();
              if (alias != null && !alias.isBlank()) {
                fileCtx.aliasToFqn.put(alias, cls);
              }
            }
          }

          // star imports (import a.b.*)
          for (ImportNode imp : mn.getStarImports()) {
            String pkg = imp.getPackageName();
            if (pkg != null && !pkg.isBlank()) fileCtx.starImports.add(trimDot(pkg));
          }

          // NOTE: static imports (single/star) import MEMBERS, not type simple names.
          // Do NOT add them to visibility for types.

          // report package symbol (best-effort)
          if (!fileCtx.pkg.isBlank()) {
            reporter.reportPackage(fileCtx.pkg, new Location(fileUri,
                new Range(new Position(0, 0), new Position(0, 1))));
          }

          // visit classes under this module
          for (ClassNode cn : mn.getClasses()) {
            visitClass(fileUri, cn, reporter, fileCtx);
          }
        } else if (n instanceof ClassNode cn) {
          visitClass(fileUri, cn, reporter, fileCtx);
        }
      }

    } catch (MultipleCompilationErrorsException mce) {
      // Convert Groovy compiler errors to our Diagnostic model
      for (Message msg : mce.getErrorCollector().getErrors()) {
        if (msg instanceof SyntaxErrorMessage sem) {
          SyntaxException se = sem.getCause();
          Range r = fromGroovyPos(se.getStartLine(), se.getStartColumn(), se.getLine(), se.getEndColumn());
          diags.add(new Diagnostic(r, se.getOriginalMessage(), Diagnostic.Severity.ERROR, id(), "syntax"));
        } else {
          diags.add(new Diagnostic(
              new Range(new Position(0, 0), new Position(0, 1)),
              msg.toString(), Diagnostic.Severity.ERROR, id(), "error"));
        }
      }
    } catch (Throwable t) {
      diags.add(new Diagnostic(new Range(new Position(0, 0), new Position(0, 1)),
          "Parse error: " + t.getMessage(), Diagnostic.Severity.ERROR, id(), "parse"));
    }

    // Save ctx after successful/attempted parse (best-effort for resolveSymbol/completions)
    ctxByUri.put(fileUri, fileCtx);
    return diags;
  }


  @Override
  public SymbolInfo resolveSymbol(String fileUri, String symbolName, CoreQuery core) {
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

    // Alias first: import foo.Bar as B
    String aliasHit = ctx.aliasToFqn.get(symbolName);
    if (aliasHit != null) {
      return core.findByFqn(aliasHit).orElse(null);
    }

    // Fallback: derive package from source text if we didn’t capture it during indexing
    if (ctx.pkg == null || ctx.pkg.isBlank()) {
      String content = contentByUri.get(fileUri);
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
        if (simpleName(s.getFqName()).equals(symbolName) &&
            (s.getKind() == SymbolInfo.Kind.CLASS ||
                s.getKind() == SymbolInfo.Kind.INTERFACE ||
                s.getKind() == SymbolInfo.Kind.ENUM ||
                s.getKind() == SymbolInfo.Kind.ANNOTATION)) {
          return s;
        }
      }
    }
    var candidates = new ArrayList<SymbolInfo>();
    for (var s : core.allInPackage(ctx.pkg == null ? "" : ctx.pkg)) {
      if (simpleName(s.getFqName()).equals(symbolName)) {
        candidates.add(s);
      }
    }
    if (candidates.size() == 1) return candidates.getFirst();
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
      String typedPkg     = prefix.substring(0, lastDot);
      String simplePrefix = prefix.substring(lastDot + 1);

      boolean starVisibleForTypedPkg = hasStarImportFor(typedPkg, content);

      for (var s : core.allInPackage(typedPkg)) {
        if (!isType(s)) continue;
        String fqn    = s.getFqName();
        String simple = simpleName(fqn);
        if (!simple.startsWith(simplePrefix)) continue;

        // visible if same-pkg, single-import, alias, star-import (ctx) OR a raw "import pkg.*" line exists
      boolean visible  = isTypeVisibleInFile(fqn, ctx) || starVisibleForTypedPkg;
      boolean needEdit = !visible;

      add(out, s, needEdit ? content : null); // attach auto-import only when NOT visible
    }
      if (!out.isEmpty()) {
        return List.copyOf(out.values());
      }

      collectMembersFromReceiver(core, qualifierOf(prefix), simplePrefix, ctx.primaryClassFqn, out);
      if (!out.isEmpty()) {
        return List.copyOf(out.values());
      }
    }

    // ----- undotted prefix: e.g. "Ba" -----
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
      core.findByFqn(imp).ifPresent(sym -> {
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
      core.findByFqn(fqn).ifPresent(sym -> {
        out.put("__alias__:" + alias,
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


  @Override public void forget(String fileUri) {
    ctxByUri.remove(fileUri);
    contentByUri.remove(fileUri);
    clearHierarchy(fileUri);
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
    String fqn = name.contains(".")
        ? name
        : (ctx.pkg == null || ctx.pkg.isBlank() ? name : ctx.pkg + "." + name);

    boolean isInterface = cn.isInterface();
    boolean isEnum      = cn.isEnum();
    boolean isAnno      = cn.isAnnotationDefinition();

    reporter.reportClass(fqn, new Location(fileUri, toRange(cn)), isInterface, isEnum, isAnno);
    recordTypeHierarchy(fileUri, fqn, cn, ctx);
    if (ctx.primaryClassFqn.isBlank()) {
      ctx.primaryClassFqn = fqn;
    }

    // Methods
    for (MethodNode mn : cn.getMethods()) {
      if (mn.getDeclaringClass() != cn) continue;
      reporter.reportMethod(fqn, mn.getName(), methodSig(mn, ctx), new Location(fileUri, toRange(mn)),
          modifiers(mn.getModifiers()));
    }
    // Fields
    for (FieldNode fn : cn.getFields()) {
      if (fn.getDeclaringClass() != cn) continue;
      reporter.reportField(fqn, fn.getName(), typeOf(fn.getType(), ctx), new Location(fileUri, toRange(fn)),
          modifiers(fn.getModifiers()));
    }
    // Groovy properties as fields
    for (PropertyNode pn : cn.getProperties()) {
      reporter.reportField(fqn, pn.getName(), typeOf(pn.getType(), ctx), new Location(fileUri, toRange(pn)),
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
        public void visitDeclarationExpression(org.codehaus.groovy.ast.expr.DeclarationExpression expression) {
          org.codehaus.groovy.ast.expr.Expression left = expression.getLeftExpression();
          if (left instanceof org.codehaus.groovy.ast.expr.VariableExpression variableExpression) {
            ClassNode originType = variableExpression.getOriginType();
            if (originType != null && !variableExpression.isDynamicTyped()) {
              reporter.reportField(fqn, variableExpression.getName(), typeOf(originType, ctx),
                  new Location(fileUri, toRange(expression)), Set.of());
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
      el = sl; ec = Math.max(sc + 1, 1);
    }
    return new Range(new Position(sl, sc), new Position(el, ec));
  }

  private static Range fromGroovyPos(int sLine1, int sCol1, int eLine1, int eCol1) {
    int sl = safeZero(sLine1 - 1), sc = safeZero(sCol1 - 1);
    int el = safeZero(eLine1 - 1),  ec = safeZero(eCol1 - 1);
    if (el < sl || (el == sl && ec < sc)) { el = sl; ec = Math.max(sc + 1, 1); }
    return new Range(new Position(sl, sc), new Position(el, ec));
  }

  private static int safeZero(int x) { return Math.max(x, 0); }

  private MethodSignature methodSig(MethodNode mn, FileCtx ctx) {
    List<JvmType> parameterTypes = new ArrayList<>();
    List<String> parameterNames = new ArrayList<>();
    for (Parameter parameter : mn.getParameters()) {
      parameterTypes.add(typeOf(parameter.getType(), ctx));
      parameterNames.add(parameter.getName());
    }
    return new MethodSignature(parameterTypes, typeOf(mn.getReturnType(), ctx),
        parameterNames, List.of(), List.of(), modifiers(mn.getModifiers()));
  }

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
    List<JvmType> typeArguments = node.getGenericsTypes() == null ? List.of() : Arrays.stream(node.getGenericsTypes())
        .map(genericsType -> {
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
    List<String> visibleImports = new ArrayList<>(ctx.singleImports.size() + ctx.starImports.size()
        + DEFAULT_SINGLE_IMPORTS.size() + DEFAULT_STAR_IMPORTS.size());
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

  private static String fallbackResolveTypeName(String name, FileCtx ctx, List<String> visibleImports) {
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
    out.addAll(a); out.addAll(b);
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
    var edits = (content == null) ? java.util.List.<se.alipsa.jvmpls.core.model.TextEdit>of()
        : maybeImportEdit(content, fqn);
    out.put(fqn, new CompletionItem(simple, fqn, simple, s.getLocation(), edits));
  }

  private static void addMember(java.util.Map<String, CompletionItem> out, SymbolInfo s, String label) {
    String key = memberCompletionKey(s, label);
    if (out.containsKey(key)) return;
    String typeDetail = s.getResolvedType() != null
        ? s.getResolvedType().displayName()
        : s.getMethodSignature() != null
            ? s.getMethodSignature().returnType().displayName()
            : s.getSignature();
    String detail = s.getMethodSignature() != null
        ? s.getContainerFqName() + JvmTypes.toLegacyMethodSignature(s.getMethodSignature())
        : s.getContainerFqName();
    out.put(key, new CompletionItem(label, detail, label, s.getLocation(), List.of(), typeDetail));
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
    String owner  = fqn.substring(0, fqn.length() - simple.length() - 1);

    // No import needed in same package, default star imports, or Groovy's default single imports
    if (owner.equals(pkg) || DEFAULT_STAR_IMPORTS.contains(owner) || DEFAULT_SINGLE_IMPORTS.contains(fqn)) {
      return List.of();
    }

    // Already imported explicitly or via (non-static) star?
    var IMPORT_WITH_ALIAS = java.util.regex.Pattern.compile(
        "(?m)^\\s*import(?:\\s+(static))?\\s+([\\w.]+)(?:\\s+as\\s+(\\w+))?\\s*$");
    var im = IMPORT_WITH_ALIAS.matcher(content);
    while (im.find()) {
      String isStatic = im.group(1);
      String imp      = im.group(2);
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
      if (c == '\n') { line++; col = 0; } else { col++; }
    }

    // Build edit text (Groovy has no semicolons)
    String prefixNL = (insertAt == 0 || content.charAt(Math.max(0, insertAt - 1)) == '\n') ? "" : "\n";
    String newText  = prefixNL + "import " + fqn + "\n";

    var range = new Range(
        new Position(line, col),
        new Position(line, col)
    );
    var edit = new TextEdit(range, newText);
    return List.of(edit);
  }

  private static void collectTypesFromPackage(CoreQuery core, String pkg, String simplePrefix,
                                              String content,
                                              java.util.Map<String, CompletionItem> out) {
    if (pkg == null || pkg.isBlank()) return;
    for (var s : core.allInPackage(pkg)) {
      if (isType(s) && simpleName(s.getFqName()).startsWith(simplePrefix)) {
        add(out, s, content);
      }
    }
  }

  private void collectMembersFromReceiver(CoreQuery core, String receiver, String memberPrefix,
                                          String currentOwnerFqn,
                                          java.util.Map<String, CompletionItem> out) {
    if (currentOwnerFqn == null || currentOwnerFqn.isBlank()) {
      return;
    }
    for (SymbolInfo symbol : core.membersOf(currentOwnerFqn)) {
      if (symbol.getKind() != SymbolInfo.Kind.FIELD) {
        continue;
      }
      if (!receiver.equals(memberName(symbol))) {
        continue;
      }
      if (symbol.getResolvedType() instanceof ClassType classType) {
        for (SymbolInfo member : core.membersOf(classType.fqName())) {
          String name = memberName(member);
          if (name.startsWith(memberPrefix) && isVisible(member, currentOwnerFqn, core)) {
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
      if (!(Character.isAlphabetic(c) || Character.isDigit(c) || c == '_' || c == '$' || c == '.')) break;
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

  private static Set<String> modifiers(int flags) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    if (Modifier.isPublic(flags)) out.add("public");
    if (Modifier.isProtected(flags)) out.add("protected");
    if (Modifier.isPrivate(flags)) out.add("private");
    if (!Modifier.isPublic(flags) && !Modifier.isProtected(flags) && !Modifier.isPrivate(flags)) out.add("package-private");
    if (Modifier.isAbstract(flags)) out.add("abstract");
    if (Modifier.isFinal(flags)) out.add("final");
    if (Modifier.isStatic(flags)) out.add("static");
    return Set.copyOf(out);
  }

  private boolean isVisible(SymbolInfo member, String currentOwnerFqn, CoreQuery core) {
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
          || isSubtypeOf(currentOwnerFqn, member.getContainerFqName(), core, new LinkedHashSet<>());
    }
    return false;
  }

  private boolean isSubtypeOf(String currentType, String targetType, CoreQuery core, Set<String> visited) {
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

  private void recordTypeHierarchy(String fileUri, String typeFqn, ClassNode classNode, FileCtx ctx) {
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

  private static String memberCompletionKey(SymbolInfo symbol, String label) {
    return switch (symbol.getKind()) {
      case FIELD -> "FIELD:" + label;
      case METHOD -> "METHOD:" + label + ":" +
          (symbol.getMethodSignature() == null ? symbol.getSignature() : JvmTypes.toLegacyMethodSignature(symbol.getMethodSignature()));
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
      var IMPORT_WITH_ALIAS = java.util.regex.Pattern.compile(
          "(?m)^\\s*import(?:\\s+(static))?\\s+([\\w.]+)(?:\\s+as\\s+(\\w+))?\\s*$");
      var m = IMPORT_WITH_ALIAS.matcher(content);
      while (m.find()) {
        String isStatic = m.group(1);
        String target   = m.group(2);
        String alias    = m.group(3);
        if (isStatic != null) continue;                 // static imports are members, not types
        if (STAR_SUFFIX.matcher(target).find()) {
          ctx.starImports.add(normPkg(target.substring(0, target.length() - 2))); // "a.b.*" -> "a.b"
        } else {
          ctx.singleImports.add(target);
          if (alias != null && !alias.isBlank()) ctx.aliasToFqn.put(alias, target);
        }
      }
    }
  }

  /** True if fqn already visible in file by package, single import, alias, or star import (incl. Groovy defaults). */
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
