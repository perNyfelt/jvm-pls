package se.alipsa.jvmpls.java;

import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.JvmLangPlugin;
import se.alipsa.jvmpls.core.PluginEnvironment;
import se.alipsa.jvmpls.core.SymbolReporter;
import se.alipsa.jvmpls.core.model.*;
import se.alipsa.jvmpls.core.types.ClassType;
import se.alipsa.jvmpls.core.types.JvmType;
import se.alipsa.jvmpls.core.types.TypeResolver;
import se.alipsa.jvmpls.core.types.JvmTypes;
import se.alipsa.jvmpls.core.types.MethodSignature;

import javax.tools.*;
import javax.lang.model.element.Modifier;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import se.alipsa.jvmpls.core.model.Diagnostic;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class JavaPlugin implements JvmLangPlugin {

  private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();
  private final Map<String,String> contentByUri = new ConcurrentHashMap<>();
  private final Map<String, List<String>> directSupertypesByType = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> typesByUri = new ConcurrentHashMap<>();
  private volatile TypeResolver typeResolver;

  private static final java.util.regex.Pattern PKG =
      Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
  private static final java.util.regex.Pattern CLASS_DECL =
      Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)\\b");
  private static final java.util.regex.Pattern IMPORT =
      // Capture both single-type and on-demand imports:
      // group(1) examples: "a.b.C", "a.b.*"
      Pattern.compile("(?m)^\\s*import(?:\\s+static)?\\s+([\\w.]+(?:\\.\\*)?)\\s*;");
  private static final List<String> JAVA_DEFAULT_STAR_IMPORTS = List.of("java.lang");

  @Override public String id() { return "java"; }
  @Override public Set<String> fileExtensions() { return Set.of("java"); }

  @Override
  public void configure(PluginEnvironment env) {
    typeResolver = new TypeResolver(env.core());
  }

  @Override
  public List<Diagnostic> index(String fileUri, String content, SymbolReporter reporter) {
    contentByUri.put(fileUri, content);
    clearHierarchy(fileUri);
    var out = new ArrayList<Diagnostic>();

    try (var fm = COMPILER.getStandardFileManager(null, null, null)) {
      JavaFileObject mem = new SimpleJavaFileObject(URI.create(fileUri), JavaFileObject.Kind.SOURCE) {
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return content; }
      };

      var options   = List.of("-proc:none", "-source", "21");
      var sink      = new java.io.StringWriter();        // swallow compiler output
      var pw        = new java.io.PrintWriter(sink);
      var collector = new javax.tools.DiagnosticCollector<JavaFileObject>();

      JavacTask task = (JavacTask) COMPILER.getTask(pw, fm, collector, options, null, List.of(mem));
      Trees trees = Trees.instance(task);

      for (CompilationUnitTree cu : task.parse()) {
        String pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
        List<String> visibleImports = visibleImports(cu);
        if (!pkg.isEmpty()) {
          reporter.reportPackage(pkg, new Location(fileUri, new Range(new Position(0, 0), new Position(0, 1))));
        }

        cu.accept(new TreeScanner<Void, Void>() {
          String owner; // current enclosing FQN
          int methodDepth;

          @Override public Void visitClass(ClassTree node, Void p) {
            String simple = node.getSimpleName().toString();
            if (!simple.isEmpty()) {
              String fqn = (pkg.isEmpty() ? "" : pkg + ".") + simple;
              boolean isInterface = node.getKind() == Tree.Kind.INTERFACE;
              boolean isEnum      = node.getKind() == Tree.Kind.ENUM;
              boolean isAnno      = node.getKind() == Tree.Kind.ANNOTATION_TYPE;

              reporter.reportClass(fqn, new Location(fileUri, toRange(cu, node, trees)), isInterface, isEnum, isAnno);
              recordTypeHierarchy(fileUri, fqn, node, pkg, visibleImports);

              // descend with this owner and restore afterwards (handles nested types)
              String prev = owner;
              owner = fqn;
              try {
                return super.visitClass(node, p);
              } finally {
                owner = prev;
              }
            }
            return super.visitClass(node, p);
          }

          @Override public Void visitMethod(MethodTree node, Void p) {
            methodDepth++;
            if (owner != null) {
              reporter.reportMethod(owner, node.getName().toString(),
                  methodSig(node, pkg, visibleImports), new Location(fileUri, toRange(cu, node, trees)),
                  modifiers(node.getModifiers().getFlags()));
            }
            try {
              return super.visitMethod(node, p);
            } finally {
              methodDepth--;
            }
          }

          @Override public Void visitVariable(VariableTree node, Void p) {
            if (owner != null && methodDepth == 0 && node.getName() != null) {
              JvmType type = resolveType(node.getType() == null ? "java.lang.Object" : node.getType().toString(),
                  pkg, visibleImports);
              reporter.reportField(owner, node.getName().toString(), type,
                  new Location(fileUri, toRange(cu, node, trees)),
                  modifiers(node.getModifiers().getFlags()));
            }
            return super.visitVariable(node, p);
          }
        }, null);
      }

    } catch (IOException e) {
      out.add(new Diagnostic(new Range(new Position(0,0), new Position(0,1)),
          "IO while parsing: " + e.getMessage(), Diagnostic.Severity.ERROR, id(), "io"));
    } catch (Throwable t) {
      out.add(new Diagnostic(new Range(new Position(0,0), new Position(0,1)),
          "Parse error: " + t.getMessage(), Diagnostic.Severity.ERROR, id(), "parse"));
    }
    return out;
  }



  @Override
  public SymbolInfo resolveSymbol(String fileUri, String symbolName, CoreQuery core) {
    if (symbolName == null || symbolName.isBlank()) return null;

    // If it looks like an FQN, try directly.
    int dot = symbolName.indexOf('.');
    if (dot >= 0) {
      var direct = core.findByFqn(symbolName);
      if (direct.isPresent()) return direct.get();
      symbolName = symbolName.substring(0, dot); // use leftmost identifier
    }

    // Use cached source to infer package/imports.
    String content = contentByUri.get(fileUri);
    if (content != null) {
      // 1) Same-package resolution
      String pkg = find(PKG, content);
      if (pkg != null && !pkg.isBlank()) {
        String fqn = pkg + "." + symbolName;
        var hit = core.findByFqn(fqn);
        if (hit.isPresent()) return hit.get();
      }

      // 2) Explicit single-type imports (import a.b.C;)
      var im = IMPORT.matcher(content);
      while (im.find()) {
        String imp = im.group(1);
        if (imp.endsWith("." + symbolName)) {
          return core.findByFqn(imp).orElse(null);
        }
      }

      // 3) On-demand imports (import a.b.*;) — best-effort
      im.reset();
      while (im.find()) {
        String imp = im.group(1);
        if (imp.endsWith(".*")) {
          String p = imp.substring(0, imp.length() - 2);
          for (var s : core.allInPackage(p)) {
            if (simpleName(s.getFqName()).equals(symbolName) &&
                (s.getKind() == SymbolInfo.Kind.CLASS ||
                    s.getKind() == SymbolInfo.Kind.INTERFACE ||
                    s.getKind() == SymbolInfo.Kind.ENUM)) {
              return s;
            }
          }
        }
      }

      // (Optional: java.lang.* fallback if you index JDK symbols later)
    }

    return null;
  }

  @Override public void forget(String fileUri) {
    contentByUri.remove(fileUri);
    clearHierarchy(fileUri);
  }

  @Override
  public List<CompletionItem> completions(String fileUri, Position position, CoreQuery core) {
    String content = contentByUri.get(fileUri);
    if (content == null) return List.of();

    String prefix = completionPrefix(content, position); // may include dots
    var out = new java.util.LinkedHashMap<String, CompletionItem>(); // fqName -> item

    // 1) Dotted prefix => collect by explicit package
    int lastDot = prefix.lastIndexOf('.');
    if (lastDot >= 0) {
      String qualifier = prefix.substring(0, lastDot);
      String simplePrefix = prefix.substring(lastDot + 1);
      int before = out.size();
      collectTypesFromPackage(core, qualifier, simplePrefix, content, out);
      if (out.size() == before) {
        collectMembersFromReceiver(core, qualifier, simplePrefix, content, out);
      }
      if (!out.isEmpty()) {
        return List.copyOf(out.values());
      }
    }

    // 2) Undotted OR fallback: visible types (same pkg + imports + defaults)
    String simplePrefix = (lastDot >= 0) ? prefix.substring(lastDot + 1) : prefix;

    String pkg = find(PKG, content); // your existing pattern for `package ...;`
    if (pkg != null && !pkg.isBlank()) {
      collectTypesFromPackage(core, pkg, simplePrefix, content, out);
    }

    var m = IMPORT.matcher(content); // your existing `import ...;` pattern
    while (m.find()) {
      String imp = m.group(1);
      if (imp.endsWith(".*")) {
        collectTypesFromPackage(core, imp.substring(0, imp.length() - 2), simplePrefix, content, out);
      } else {
        core.findByFqn(imp).ifPresent(sym -> {
          if (isType(sym) && simpleName(sym.getFqName()).startsWith(simplePrefix)) {
            add(out, sym, content);
          }
        });
      }
    }

    // Defaults (java.lang)
    for (String p : JAVA_DEFAULT_STAR_IMPORTS) {
      collectTypesFromPackage(core, p, simplePrefix, content, out);
    }

    return List.copyOf(out.values());
  }


  private static Range toRange(CompilationUnitTree cu, Tree node, Trees trees) {
    LineMap lm = cu.getLineMap();
    SourcePositions sp = trees.getSourcePositions();
    long s = sp.getStartPosition(cu, node), e = sp.getEndPosition(cu, node);
    int sl = (int)(lm.getLineNumber(s) - 1), sc = (int)(lm.getColumnNumber(s) - 1);
    int el = (int)(lm.getLineNumber(e) - 1), ec = (int)(lm.getColumnNumber(e) - 1);
    return new Range(new Position(sl, sc), new Position(el, ec));
  }

  private MethodSignature methodSig(MethodTree mt, String pkg, List<String> visibleImports) {
    List<JvmType> parameterTypes = new ArrayList<>();
    List<String> parameterNames = new ArrayList<>();
    for (var parameter : mt.getParameters()) {
      parameterTypes.add(resolveType(parameter.getType() == null ? "java.lang.Object" : parameter.getType().toString(),
          pkg, visibleImports));
      parameterNames.add(parameter.getName().toString());
    }
    JvmType returnType = resolveType(mt.getReturnType() == null ? "void" : mt.getReturnType().toString(),
        pkg, visibleImports);
    return new MethodSignature(parameterTypes, returnType, parameterNames, List.of(), List.of(), Set.of());
  }

  // tiny helpers
  private static String find(java.util.regex.Pattern p, String s) {
    var m = p.matcher(s);
    return m.find() ? m.group(1) : null;
  }
  private static String simpleName(String fqn) {
    int i = fqn.lastIndexOf('.');
    return i < 0 ? fqn : fqn.substring(i + 1);
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

  private static boolean isType(SymbolInfo s) {
    return switch (s.getKind()) {
      case CLASS, INTERFACE, ENUM, ANNOTATION -> true;
      default -> false;
    };
  }

  private void collectMembersFromReceiver(CoreQuery core, String receiver, String memberPrefix,
                                          String content, java.util.Map<String, CompletionItem> out) {
    String ownerClass = primaryClassFqn(content);
    if (ownerClass == null) {
      return;
    }
    for (SymbolInfo symbol : core.membersOf(ownerClass)) {
      if (symbol.getKind() != SymbolInfo.Kind.FIELD) {
        continue;
      }
      if (!receiver.equals(memberName(symbol))) {
        continue;
      }
      JvmType resolvedType = symbol.getResolvedType();
      if (resolvedType instanceof ClassType classType) {
        for (SymbolInfo member : core.membersOf(classType.fqName())) {
          String name = memberName(member);
          if (name.startsWith(memberPrefix) && isVisible(member, ownerClass, core)) {
            addMember(out, member, name);
          }
        }
      }
      return;
    }
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

  // Overloads for add(...) — keep both
  private static void add(java.util.Map<String, CompletionItem> out, SymbolInfo s, String content) {
    String fqn = s.getFqName();
    if (out.containsKey(fqn)) return;
    String simple = simpleName(fqn);
    var edits = (content == null) ? java.util.List.<se.alipsa.jvmpls.core.model.TextEdit>of()
        : maybeImportEdit(content, fqn); // your auto-import builder
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
  private static void add(java.util.Map<String, CompletionItem> out, SymbolInfo s) {
    add(out, s, null);
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

  private static java.util.List<TextEdit> maybeImportEdit(String content, String fqn) {
    String pkg = find(PKG, content); // your existing PKG pattern
    String simple = simpleName(fqn);
    String owner = fqn.substring(0, fqn.length() - simple.length() - 1);

    // already in same package?
    if (owner.equals(pkg)) return java.util.List.of();

    // already imported?
    var m = IMPORT.matcher(content);
    while (m.find()) {
      String imp = m.group(1);
      if (imp.equals(fqn) || imp.equals(owner + ".*")) return java.util.List.of();
    }

    // insert after last import or after package decl
    int insertAt = -1;
    int lastImportEnd = -1;
    m.reset();
    while (m.find()) lastImportEnd = m.end();
    if (lastImportEnd >= 0) insertAt = lastImportEnd;
    else {
      var pm = PKG.matcher(content);
      if (pm.find()) insertAt = pm.end();
      else insertAt = 0;
    }

    // Build edit at (line/col) for `insertAt`
    int line = 0, col = 0;
    for (int i = 0; i < insertAt; i++) {
      char c = content.charAt(i);
      if (c == '\n') { line++; col = 0; } else { col++; }
    }
    Range r = new Range(new Position(line, col), new Position(line, col));
    String sep = (insertAt == 0) ? "" : (content.charAt(insertAt - 1) == '\n' ? "" : "\n");
    String text = sep + "import " + fqn + ";\n";
    return java.util.List.of(new TextEdit(r, text));
  }

  private static List<String> visibleImports(CompilationUnitTree cu) {
    List<String> imports = new ArrayList<>(JAVA_DEFAULT_STAR_IMPORTS.stream()
        .map(pkg -> pkg + ".*")
        .toList());
    for (ImportTree importTree : cu.getImports()) {
      String imported = importTree.getQualifiedIdentifier().toString();
      if (importTree.isStatic()) {
        continue;
      }
      imports.add(imported);
    }
    return imports;
  }

  private JvmType resolveType(String rawType, String pkg, List<String> visibleImports) {
    TypeResolver resolver = typeResolver;
    if (resolver == null) {
      return JvmTypes.fromSource(rawType, simpleName -> fallbackResolveImportedTypeName(simpleName, pkg, visibleImports));
    }
    return JvmTypes.fromSource(rawType, simpleName -> {
      String resolved = resolver.resolveClassName(simpleName, pkg, visibleImports);
      if (!Objects.equals(resolved, simpleName)) {
        return resolved;
      }
      return fallbackResolveImportedTypeName(simpleName, pkg, visibleImports);
    });
  }

  private static String fallbackResolveImportedTypeName(String simpleName, String pkg, List<String> visibleImports) {
    if (simpleName == null || simpleName.isBlank() || simpleName.contains(".")) {
      return simpleName;
    }
    if (JvmTypes.isPrimitive(simpleName) || "void".equals(simpleName)) {
      return simpleName;
    }
    for (String visibleImport : visibleImports) {
      if (!visibleImport.endsWith(".*") && visibleImport.endsWith("." + simpleName)) {
        return visibleImport;
      }
    }
    for (String visibleImport : visibleImports) {
      if (visibleImport.endsWith(".*") && !"java.lang.*".equals(visibleImport)) {
        String candidate = visibleImport.substring(0, visibleImport.length() - 2) + "." + simpleName;
        if (isKnownRuntimeType(candidate)) {
          return candidate;
        }
      }
    }
    if (visibleImports.contains("java.lang.*") && isKnownRuntimeType("java.lang." + simpleName)) {
      return "java.lang." + simpleName;
    }
    return (pkg == null || pkg.isBlank()) ? simpleName : pkg + "." + simpleName;
  }

  private static boolean isKnownRuntimeType(String fqn) {
    try {
      Class.forName(fqn, false, JavaPlugin.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError ignored) {
      return false;
    }
  }

  private static Set<String> modifiers(Set<Modifier> flags) {
    LinkedHashSet<String> modifiers = flags.stream()
        .map(flag -> flag.name().toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    if (!modifiers.contains("public") && !modifiers.contains("protected") && !modifiers.contains("private")) {
      modifiers.add("package-private");
    }
    return Set.copyOf(modifiers);
  }

  private static String primaryClassFqn(String content) {
    String pkg = find(PKG, content);
    var matcher = CLASS_DECL.matcher(content);
    if (!matcher.find()) {
      return null;
    }
    String simple = matcher.group(1);
    return (pkg == null || pkg.isBlank()) ? simple : pkg + "." + simple;
  }

  private boolean isVisible(SymbolInfo member, String currentOwner, CoreQuery core) {
    Set<String> modifiers = member.getModifiers();
    if (member.getContainerFqName().equals(currentOwner)) {
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
    String currentPackage = packageName(currentOwner);
    String ownerPackage = packageName(member.getContainerFqName());
    if (modifiers.contains("package-private")) {
      return Objects.equals(currentPackage, ownerPackage);
    }
    if (modifiers.contains("protected")) {
      return Objects.equals(currentPackage, ownerPackage)
          || isSubtypeOf(currentOwner, member.getContainerFqName(), core, new LinkedHashSet<>());
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

  private void recordTypeHierarchy(String fileUri, String typeFqn, ClassTree node, String pkg, List<String> visibleImports) {
    ArrayList<String> supertypes = new ArrayList<>();
    Tree extendsClause = node.getExtendsClause();
    if (extendsClause != null) {
      JvmType extendsType = resolveType(extendsClause.toString(), pkg, visibleImports);
      if (extendsType instanceof ClassType classType) {
        supertypes.add(classType.fqName());
      }
    }
    for (Tree implemented : node.getImplementsClause()) {
      JvmType interfaceType = resolveType(implemented.toString(), pkg, visibleImports);
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

  private static String packageName(String fqn) {
    int lastDot = fqn.lastIndexOf('.');
    return lastDot < 0 ? "" : fqn.substring(0, lastDot);
  }

  private static String memberCompletionKey(SymbolInfo symbol, String label) {
    return switch (symbol.getKind()) {
      case FIELD -> "FIELD:" + label;
      case METHOD -> "METHOD:" + label + ":" +
          (symbol.getMethodSignature() == null ? symbol.getSignature() : JvmTypes.toLegacyMethodSignature(symbol.getMethodSignature()));
      default -> symbol.getFqName();
    };
  }

}
