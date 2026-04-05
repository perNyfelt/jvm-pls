package se.alipsa.jvmpls.java;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.lang.model.element.Modifier;
import javax.tools.*;

import com.sun.source.tree.*;
import com.sun.source.util.*;

import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.JvmLangPlugin;
import se.alipsa.jvmpls.core.PluginEnvironment;
import se.alipsa.jvmpls.core.StructuralHash;
import se.alipsa.jvmpls.core.SymbolReporter;
import se.alipsa.jvmpls.core.model.*;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.types.ClassType;
import se.alipsa.jvmpls.core.types.JvmType;
import se.alipsa.jvmpls.core.types.JvmTypes;
import se.alipsa.jvmpls.core.types.MethodSignature;
import se.alipsa.jvmpls.core.types.TypeResolver;

public final class JavaPlugin implements JvmLangPlugin {

  private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();
  private final Map<String, String> contentByUri = new ConcurrentHashMap<>();
  private final Map<String, List<String>> directSupertypesByType = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> typesByUri = new ConcurrentHashMap<>();
  private volatile CoreQuery coreQuery;
  private volatile TypeResolver typeResolver;

  /** Per-file structural hash for detecting body-only vs structural changes. */
  private final Map<String, String> structuralHashByUri = new ConcurrentHashMap<>();

  /** Cached diagnostics from the last successful index, keyed by file URI. */
  private final Map<String, List<Diagnostic>> cachedDiagsByUri = new ConcurrentHashMap<>();

  private static final java.util.regex.Pattern PKG =
      Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
  private static final java.util.regex.Pattern CLASS_DECL =
      Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)\\b");
  private static final java.util.regex.Pattern IMPORT =
      // Capture both single-type and on-demand imports:
      // group(1) examples: "a.b.C", "a.b.*"
      Pattern.compile("(?m)^\\s*import(?:\\s+static)?\\s+([\\w.]+(?:\\.\\*)?)\\s*;");
  private static final List<String> JAVA_DEFAULT_STAR_IMPORTS = List.of("java.lang");

  @Override
  public String id() {
    return "java";
  }

  @Override
  public Set<String> fileExtensions() {
    return Set.of("java");
  }

  @Override
  public void configure(PluginEnvironment env) {
    coreQuery = env.core();
    typeResolver = new TypeResolver(env.core());
  }

  @Override
  public List<Diagnostic> index(String fileUri, String content, SymbolReporter reporter) {
    contentByUri.put(fileUri, content);

    // Check structural hash: if only method bodies changed, skip full re-parse
    String newHash = StructuralHash.compute(content);
    String oldHash = structuralHashByUri.put(fileUri, newHash);
    if (oldHash != null && oldHash.equals(newHash)) {
      List<Diagnostic> cached = cachedDiagsByUri.get(fileUri);
      if (cached != null) {
        return cached;
      }
    }

    clearHierarchy(fileUri);
    var out = new ArrayList<Diagnostic>();

    try (var fm = COMPILER.getStandardFileManager(null, null, null)) {
      JavaFileObject mem =
          new SimpleJavaFileObject(URI.create(fileUri), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
              return content;
            }
          };

      var options = List.of("-proc:none", "-source", "21");
      var sink = new java.io.StringWriter(); // swallow compiler output
      var pw = new java.io.PrintWriter(sink);
      var collector = new javax.tools.DiagnosticCollector<JavaFileObject>();

      JavacTask task = (JavacTask) COMPILER.getTask(pw, fm, collector, options, null, List.of(mem));
      Trees trees = Trees.instance(task);

      List<CompilationUnitTree> units = new ArrayList<>();
      for (CompilationUnitTree cu : task.parse()) {
        units.add(cu);
      }
      for (CompilationUnitTree cu : units) {
        String pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
        List<String> visibleImports = visibleImports(cu);
        reportImportReferences(fileUri, cu, pkg, visibleImports, reporter, trees);
        if (!pkg.isEmpty()) {
          reporter.reportPackage(
              pkg, new Location(fileUri, new Range(new Position(0, 0), new Position(0, 1))));
        }

        cu.accept(
            new TreeScanner<Void, Void>() {
              String owner; // current enclosing FQN
              String callableFqn;
              int methodDepth;
              final Deque<Map<String, String>> localTypes = new ArrayDeque<>();

              @Override
              public Void visitClass(ClassTree node, Void p) {
                String simple = node.getSimpleName().toString();
                if (!simple.isEmpty()) {
                  String fqn = (pkg.isEmpty() ? "" : pkg + ".") + simple;
                  boolean isInterface = node.getKind() == Tree.Kind.INTERFACE;
                  boolean isEnum = node.getKind() == Tree.Kind.ENUM;
                  boolean isAnno = node.getKind() == Tree.Kind.ANNOTATION_TYPE;

                  reporter.reportClass(
                      fqn,
                      new Location(fileUri, toRange(cu, node, trees)),
                      isInterface,
                      isEnum,
                      isAnno);
                  recordTypeHierarchy(fileUri, fqn, node, pkg, visibleImports);
                  reporter.reportDirectSupertypes(
                      fqn, directSupertypesByType.getOrDefault(fqn, List.of()));
                  reportHierarchyReferences(
                      fileUri, cu, node, pkg, visibleImports, reporter, trees);

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

              @Override
              public Void visitMethod(MethodTree node, Void p) {
                methodDepth++;
                localTypes.push(new LinkedHashMap<>());
                String previousCallable = callableFqn;
                try {
                  if (owner != null) {
                    MethodSignature signature = methodSig(node, pkg, visibleImports);
                    Location location = new Location(fileUri, toRange(cu, node, trees));
                    if (node.getReturnType() == null || node.getName().contentEquals("<init>")) {
                      callableFqn = owner + "#<init>" + JvmTypes.toLegacyMethodSignature(signature);
                      reporter.reportConstructor(
                          owner, signature, location, modifiers(node.getModifiers().getFlags()));
                    } else {
                      callableFqn =
                          owner
                              + "#"
                              + node.getName()
                              + JvmTypes.toLegacyMethodSignature(signature);
                      reporter.reportMethod(
                          owner,
                          node.getName().toString(),
                          signature,
                          location,
                          modifiers(node.getModifiers().getFlags()));
                      reportTypeReference(
                          node.getReturnType(), pkg, visibleImports, location, reporter);
                    }
                    for (VariableTree parameter : node.getParameters()) {
                      String resolvedType = typeFqn(parameter.getType(), pkg, visibleImports);
                      if (resolvedType != null) {
                        localTypes.peek().put(parameter.getName().toString(), resolvedType);
                        reporter.reportReference(
                            resolvedType,
                            new Location(fileUri, toRange(cu, parameter.getType(), trees)));
                      }
                    }
                  }
                  return super.visitMethod(node, p);
                } finally {
                  callableFqn = previousCallable;
                  localTypes.pop();
                  methodDepth--;
                }
              }

              @Override
              public Void visitBlock(BlockTree node, Void p) {
                if (methodDepth > 0) {
                  localTypes.push(new LinkedHashMap<>());
                }
                try {
                  return super.visitBlock(node, p);
                } finally {
                  if (methodDepth > 0) {
                    localTypes.pop();
                  }
                }
              }

              @Override
              public Void visitVariable(VariableTree node, Void p) {
                if (owner != null && methodDepth == 0 && node.getName() != null) {
                  JvmType type =
                      resolveType(
                          node.getType() == null ? "java.lang.Object" : node.getType().toString(),
                          pkg,
                          visibleImports);
                  reporter.reportField(
                      owner,
                      node.getName().toString(),
                      type,
                      new Location(fileUri, toRange(cu, node, trees)),
                      modifiers(node.getModifiers().getFlags()));
                  reportTypeReference(
                      node.getType(),
                      pkg,
                      visibleImports,
                      new Location(fileUri, toRange(cu, node.getType(), trees)),
                      reporter);
                } else if (methodDepth > 0 && node.getName() != null && !localTypes.isEmpty()) {
                  String resolvedType = typeFqn(node.getType(), pkg, visibleImports);
                  if (resolvedType != null) {
                    localTypes.peek().put(node.getName().toString(), resolvedType);
                    reporter.reportReference(
                        resolvedType, new Location(fileUri, toRange(cu, node.getType(), trees)));
                  }
                }
                return super.visitVariable(node, p);
              }

              @Override
              public Void visitNewClass(NewClassTree node, Void p) {
                reportConstructorReference(
                    fileUri,
                    cu,
                    node,
                    callableFqn,
                    pkg,
                    visibleImports,
                    localTypes,
                    reporter,
                    trees);
                return super.visitNewClass(node, p);
              }

              @Override
              public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
                reportMethodReference(
                    fileUri,
                    cu,
                    node,
                    callableFqn,
                    owner,
                    pkg,
                    visibleImports,
                    localTypes,
                    reporter,
                    trees);
                return super.visitMethodInvocation(node, p);
              }
            },
            null);
        reportExecutableReferences(fileUri, cu, pkg, visibleImports, reporter, trees);
        reportFileDependencies(fileUri, cu, pkg, visibleImports, reporter);
      }
      try {
        task.analyze();
      } catch (RuntimeException ignored) {
        // Diagnostics are collected below even when semantic analysis aborts early.
      }
      out.addAll(mapCompilerDiagnostics(collector));

    } catch (IOException e) {
      out.add(
          new Diagnostic(
              new Range(new Position(0, 0), new Position(0, 1)),
              "IO while parsing: " + e.getMessage(),
              Diagnostic.Severity.ERROR,
              id(),
              "io"));
    } catch (Throwable t) {
      out.add(
          new Diagnostic(
              new Range(new Position(0, 0), new Position(0, 1)),
              "Parse error: " + t.getMessage(),
              Diagnostic.Severity.ERROR,
              id(),
              "parse"));
    }
    cachedDiagsByUri.put(fileUri, List.copyOf(out));
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
            if (simpleName(s.getFqName()).equals(symbolName)
                && (s.getKind() == SymbolInfo.Kind.CLASS
                    || s.getKind() == SymbolInfo.Kind.INTERFACE
                    || s.getKind() == SymbolInfo.Kind.ENUM)) {
              return s;
            }
          }
        }
      }

      // (Optional: java.lang.* fallback if you index JDK symbols later)
    }

    return null;
  }

  @Override
  public SymbolInfo resolveSymbol(
      String fileUri, String symbolName, Position position, CoreQuery core) {
    SymbolInfo resolved = resolveSymbolAtPosition(fileUri, symbolName, position, core);
    return resolved != null ? resolved : resolveSymbol(fileUri, symbolName, core);
  }

  @Override
  public Optional<SymbolInfo> typeDefinition(String fileUri, Position position, CoreQuery core) {
    String content = contentByUri.get(fileUri);
    if (content == null) {
      return Optional.empty();
    }
    return parseTypeDefinition(fileUri, content, position, core);
  }

  @Override
  public void forget(String fileUri) {
    contentByUri.remove(fileUri);
    structuralHashByUri.remove(fileUri);
    cachedDiagsByUri.remove(fileUri);
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
        collectTypesFromPackage(
            core, imp.substring(0, imp.length() - 2), simplePrefix, content, out);
      } else {
        core.findByFqn(imp)
            .ifPresent(
                sym -> {
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

  @Override
  public Optional<SignatureHelpInfo> signatureHelp(
      String fileUri, Position position, CoreQuery core) {
    String content = contentByUri.get(fileUri);
    if (content == null) {
      return Optional.empty();
    }
    return parseCallSite(fileUri, content, position, core)
        .map(
            callSite ->
                new SignatureHelpInfo(
                    callSite.candidates().stream()
                        .map(
                            candidate ->
                                new CallableInfo(
                                    renderCallable(candidate),
                                    parameterLabels(candidate),
                                    "",
                                    candidate.getLocation()))
                        .toList(),
                    callSite.activeSignature(),
                    callSite.activeParameter()));
  }

  @Override
  public List<CodeActionInfo> codeActions(
      String fileUri, Range range, List<Diagnostic> diagnostics, CoreQuery core) {
    String content = contentByUri.get(fileUri);
    if (content == null) {
      return List.of();
    }
    ArrayList<CodeActionInfo> actions = new ArrayList<>();
    actions.addAll(autoImportActions(fileUri, content, range, core));
    organizeImportsAction(content).ifPresent(actions::add);
    return List.copyOf(actions);
  }

  private static Range toRange(CompilationUnitTree cu, Tree node, Trees trees) {
    LineMap lm = cu.getLineMap();
    SourcePositions sp = trees.getSourcePositions();
    long s = sp.getStartPosition(cu, node), e = sp.getEndPosition(cu, node);
    int sl = (int) (lm.getLineNumber(s) - 1), sc = (int) (lm.getColumnNumber(s) - 1);
    int el = (int) (lm.getLineNumber(e) - 1), ec = (int) (lm.getColumnNumber(e) - 1);
    return new Range(new Position(sl, sc), new Position(el, ec));
  }

  private MethodSignature methodSig(MethodTree mt, String pkg, List<String> visibleImports) {
    List<JvmType> parameterTypes = new ArrayList<>();
    List<String> parameterNames = new ArrayList<>();
    for (var parameter : mt.getParameters()) {
      parameterTypes.add(
          resolveType(
              parameter.getType() == null ? "java.lang.Object" : parameter.getType().toString(),
              pkg,
              visibleImports));
      parameterNames.add(parameter.getName().toString());
    }
    JvmType returnType =
        resolveType(
            mt.getReturnType() == null ? "void" : mt.getReturnType().toString(),
            pkg,
            visibleImports);
    return new MethodSignature(
        parameterTypes, returnType, parameterNames, List.of(), List.of(), Set.of());
  }

  private record SymbolCandidate(SymbolInfo symbol, int span) {}

  private record CallSite(
      List<SymbolInfo> candidates, int activeSignature, int activeParameter, int span) {}

  private SymbolInfo resolveSymbolAtPosition(
      String fileUri, String symbolName, Position position, CoreQuery core) {
    if (symbolName == null || symbolName.isBlank() || position == null) {
      return null;
    }
    String content = contentByUri.get(fileUri);
    if (content == null) {
      return null;
    }
    try (var fm = COMPILER.getStandardFileManager(null, null, null)) {
      JavaFileObject mem =
          new SimpleJavaFileObject(URI.create(fileUri), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
              return content;
            }
          };
      JavacTask task =
          (JavacTask)
              COMPILER.getTask(
                  null, fm, null, List.of("-proc:none", "-source", "21"), null, List.of(mem));
      Trees trees = Trees.instance(task);
      for (CompilationUnitTree cu : task.parse()) {
        String pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
        List<String> visibleImports = visibleImports(cu);
        SymbolCandidate candidate =
            new TreePathScanner<SymbolCandidate, Void>() {
              String owner = primaryClassFqn(content);
              final Deque<Map<String, String>> localTypes = new ArrayDeque<>();

              @Override
              public SymbolCandidate visitClass(ClassTree node, Void unused) {
                String previousOwner = owner;
                String simple = node.getSimpleName().toString();
                if (!simple.isBlank()) {
                  owner = qualifiedTypeName(simple, pkg);
                }
                try {
                  return best(
                      classCandidate(node, owner, cu, trees, position, symbolName, core),
                      super.visitClass(node, unused));
                } finally {
                  owner = previousOwner;
                }
              }

              @Override
              public SymbolCandidate visitMethod(MethodTree node, Void unused) {
                localTypes.push(new LinkedHashMap<>());
                for (VariableTree parameter : node.getParameters()) {
                  String type = typeFqn(parameter.getType(), pkg, visibleImports);
                  if (type != null) {
                    localTypes.peek().put(parameter.getName().toString(), type);
                  }
                }
                try {
                  return best(
                      methodInvocationCandidate(
                          node,
                          owner,
                          pkg,
                          visibleImports,
                          localTypes,
                          cu,
                          trees,
                          position,
                          symbolName,
                          core),
                      super.visitMethod(node, unused));
                } finally {
                  localTypes.pop();
                }
              }

              @Override
              public SymbolCandidate visitBlock(BlockTree node, Void unused) {
                localTypes.push(new LinkedHashMap<>());
                try {
                  return super.visitBlock(node, unused);
                } finally {
                  localTypes.pop();
                }
              }

              @Override
              public SymbolCandidate visitVariable(VariableTree node, Void unused) {
                if (!localTypes.isEmpty()) {
                  String type = typeFqn(node.getType(), pkg, visibleImports);
                  if (type != null && node.getName() != null) {
                    localTypes.peek().put(node.getName().toString(), type);
                  }
                }
                return best(
                    typeCandidate(
                        node.getType(), cu, trees, position, symbolName, pkg, visibleImports, core),
                    super.visitVariable(node, unused));
              }

              @Override
              public SymbolCandidate visitNewClass(NewClassTree node, Void unused) {
                return best(
                    constructorCandidate(
                        node,
                        owner,
                        pkg,
                        visibleImports,
                        localTypes,
                        cu,
                        trees,
                        position,
                        symbolName,
                        core),
                    super.visitNewClass(node, unused));
              }

              @Override
              public SymbolCandidate visitMethodInvocation(MethodInvocationTree node, Void unused) {
                return best(
                    invocationCandidate(
                        node,
                        owner,
                        pkg,
                        visibleImports,
                        localTypes,
                        cu,
                        trees,
                        position,
                        symbolName,
                        core),
                    super.visitMethodInvocation(node, unused));
              }

              @Override
              public SymbolCandidate visitMemberSelect(MemberSelectTree node, Void unused) {
                return best(
                    fieldCandidate(
                        node,
                        owner,
                        pkg,
                        visibleImports,
                        localTypes,
                        cu,
                        trees,
                        position,
                        symbolName,
                        core),
                    super.visitMemberSelect(node, unused));
              }
            }.scan(cu, null);
        if (candidate != null) {
          return candidate.symbol();
        }
      }
    } catch (IOException ignored) {
      return null;
    }
    return null;
  }

  private Optional<CallSite> parseCallSite(
      String fileUri, String content, Position position, CoreQuery core) {
    try (var fm = COMPILER.getStandardFileManager(null, null, null)) {
      JavaFileObject mem =
          new SimpleJavaFileObject(URI.create(fileUri), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
              return content;
            }
          };
      JavacTask task =
          (JavacTask)
              COMPILER.getTask(
                  null, fm, null, List.of("-proc:none", "-source", "21"), null, List.of(mem));
      Trees trees = Trees.instance(task);
      for (CompilationUnitTree cu : task.parse()) {
        String pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
        List<String> visibleImports = visibleImports(cu);
        CallSite callSite =
            new TreePathScanner<CallSite, Void>() {
              String owner = primaryClassFqn(content);
              final Deque<Map<String, String>> localTypes = new ArrayDeque<>();

              @Override
              public CallSite visitClass(ClassTree node, Void unused) {
                String previousOwner = owner;
                String simple = node.getSimpleName().toString();
                if (!simple.isBlank()) {
                  owner = qualifiedTypeName(simple, pkg);
                }
                try {
                  return bestCall(
                      super.visitClass(node, unused),
                      callSiteForClass(
                          node, owner, cu, trees, position, pkg, visibleImports, localTypes, core));
                } finally {
                  owner = previousOwner;
                }
              }

              @Override
              public CallSite visitMethod(MethodTree node, Void unused) {
                localTypes.push(new LinkedHashMap<>());
                for (VariableTree parameter : node.getParameters()) {
                  String type = typeFqn(parameter.getType(), pkg, visibleImports);
                  if (type != null) {
                    localTypes.peek().put(parameter.getName().toString(), type);
                  }
                }
                try {
                  return super.visitMethod(node, unused);
                } finally {
                  localTypes.pop();
                }
              }

              @Override
              public CallSite visitBlock(BlockTree node, Void unused) {
                localTypes.push(new LinkedHashMap<>());
                try {
                  return super.visitBlock(node, unused);
                } finally {
                  localTypes.pop();
                }
              }

              @Override
              public CallSite visitVariable(VariableTree node, Void unused) {
                if (!localTypes.isEmpty()) {
                  String type = typeFqn(node.getType(), pkg, visibleImports);
                  if (type != null) {
                    localTypes.peek().put(node.getName().toString(), type);
                  }
                }
                return super.visitVariable(node, unused);
              }

              @Override
              public CallSite visitMethodInvocation(MethodInvocationTree node, Void unused) {
                return bestCall(
                    super.visitMethodInvocation(node, unused),
                    callSiteForInvocation(
                        node,
                        owner,
                        pkg,
                        visibleImports,
                        localTypes,
                        cu,
                        trees,
                        position,
                        content,
                        core));
              }

              @Override
              public CallSite visitNewClass(NewClassTree node, Void unused) {
                return bestCall(
                    super.visitNewClass(node, unused),
                    callSiteForConstructor(
                        node,
                        owner,
                        pkg,
                        visibleImports,
                        localTypes,
                        cu,
                        trees,
                        position,
                        content,
                        core));
              }
            }.scan(cu, null);
        if (callSite != null && !callSite.candidates().isEmpty()) {
          return Optional.of(callSite);
        }
      }
    } catch (IOException ignored) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private Optional<SymbolInfo> parseTypeDefinition(
      String fileUri, String content, Position position, CoreQuery core) {
    String symbolName =
        se.alipsa.jvmpls.core.TokenUtil.tokenAt(
            content,
            se.alipsa.jvmpls.core.TokenUtil.positionToOffset(
                content, position.line, position.column));
    if (symbolName == null || symbolName.isBlank()) {
      return Optional.empty();
    }
    try (var fm = COMPILER.getStandardFileManager(null, null, null)) {
      JavaFileObject mem =
          new SimpleJavaFileObject(URI.create(fileUri), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
              return content;
            }
          };
      JavacTask task =
          (JavacTask)
              COMPILER.getTask(
                  null, fm, null, List.of("-proc:none", "-source", "21"), null, List.of(mem));
      Trees trees = Trees.instance(task);
      for (CompilationUnitTree cu : task.parse()) {
        String pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
        List<String> visibleImports = visibleImports(cu);
        SymbolCandidate candidate =
            new TreePathScanner<SymbolCandidate, Void>() {
              String owner = primaryClassFqn(content);
              final Deque<Map<String, String>> localTypes = new ArrayDeque<>();

              @Override
              public SymbolCandidate visitClass(ClassTree node, Void unused) {
                String previousOwner = owner;
                String simple = node.getSimpleName().toString();
                if (!simple.isBlank()) {
                  owner = qualifiedTypeName(simple, pkg);
                }
                try {
                  return best(
                      super.visitClass(node, unused),
                      typeDefinitionForClass(
                          node, cu, trees, position, symbolName, pkg, visibleImports, core));
                } finally {
                  owner = previousOwner;
                }
              }

              @Override
              public SymbolCandidate visitMethod(MethodTree node, Void unused) {
                localTypes.push(new LinkedHashMap<>());
                for (VariableTree parameter : node.getParameters()) {
                  String type = typeFqn(parameter.getType(), pkg, visibleImports);
                  if (type != null) {
                    localTypes.peek().put(parameter.getName().toString(), type);
                  }
                }
                try {
                  return best(
                      best(
                          super.visitMethod(node, unused),
                          typeDefinitionForMethod(
                              node, cu, trees, position, symbolName, pkg, visibleImports, core)),
                      null);
                } finally {
                  localTypes.pop();
                }
              }

              @Override
              public SymbolCandidate visitBlock(BlockTree node, Void unused) {
                localTypes.push(new LinkedHashMap<>());
                try {
                  return super.visitBlock(node, unused);
                } finally {
                  localTypes.pop();
                }
              }

              @Override
              public SymbolCandidate visitVariable(VariableTree node, Void unused) {
                if (!localTypes.isEmpty()) {
                  String type = typeFqn(node.getType(), pkg, visibleImports);
                  if (type != null && node.getName() != null) {
                    localTypes.peek().put(node.getName().toString(), type);
                  }
                }
                return best(
                    localTypeCandidate(
                        node,
                        owner,
                        pkg,
                        visibleImports,
                        localTypes,
                        cu,
                        trees,
                        position,
                        symbolName,
                        core),
                    super.visitVariable(node, unused));
              }

              @Override
              public SymbolCandidate visitIdentifier(IdentifierTree node, Void unused) {
                return best(
                    identifierTypeCandidate(
                        fileUri,
                        node,
                        owner,
                        pkg,
                        visibleImports,
                        localTypes,
                        cu,
                        trees,
                        position,
                        symbolName,
                        core),
                    super.visitIdentifier(node, unused));
              }

              @Override
              public SymbolCandidate visitMemberSelect(MemberSelectTree node, Void unused) {
                return best(
                    memberTypeCandidate(
                        node,
                        owner,
                        pkg,
                        visibleImports,
                        localTypes,
                        cu,
                        trees,
                        position,
                        symbolName,
                        core),
                    super.visitMemberSelect(node, unused));
              }

              @Override
              public SymbolCandidate visitMethodInvocation(MethodInvocationTree node, Void unused) {
                return best(
                    invocationReturnTypeCandidate(
                        node,
                        owner,
                        pkg,
                        visibleImports,
                        localTypes,
                        cu,
                        trees,
                        position,
                        symbolName,
                        core),
                    super.visitMethodInvocation(node, unused));
              }

              @Override
              public SymbolCandidate visitNewClass(NewClassTree node, Void unused) {
                return best(
                    constructedTypeCandidate(
                        node, cu, trees, position, symbolName, pkg, visibleImports, core),
                    super.visitNewClass(node, unused));
              }
            }.scan(cu, null);
        if (candidate != null) {
          return Optional.of(candidate.symbol());
        }
      }
    } catch (IOException ignored) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private static SymbolCandidate best(SymbolCandidate left, SymbolCandidate right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left.span() <= right.span() ? left : right;
  }

  private static CallSite bestCall(CallSite left, CallSite right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left.span() <= right.span() ? left : right;
  }

  private SymbolCandidate typeDefinitionForClass(
      ClassTree node,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String symbolName,
      String pkg,
      List<String> visibleImports,
      CoreQuery core) {
    return null;
  }

  private SymbolCandidate typeDefinitionForMethod(
      MethodTree node,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String symbolName,
      String pkg,
      List<String> visibleImports,
      CoreQuery core) {
    if (!contains(cu, node, trees, position) || !node.getName().contentEquals(symbolName)) {
      return null;
    }
    return symbolForTypeFqn(
        typeFqn(node.getReturnType(), pkg, visibleImports), span(toRange(cu, node, trees)), core);
  }

  private SymbolCandidate localTypeCandidate(
      VariableTree node,
      String ownerFqn,
      String pkg,
      List<String> visibleImports,
      Deque<Map<String, String>> localTypes,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String symbolName,
      CoreQuery core) {
    if (!contains(cu, node, trees, position) || !node.getName().contentEquals(symbolName)) {
      return null;
    }
    return symbolForTypeFqn(
        typeFqn(node.getType(), pkg, visibleImports), span(toRange(cu, node, trees)), core);
  }

  private SymbolCandidate identifierTypeCandidate(
      String fileUri,
      IdentifierTree node,
      String ownerFqn,
      String pkg,
      List<String> visibleImports,
      Deque<Map<String, String>> localTypes,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String symbolName,
      CoreQuery core) {
    if (!contains(cu, node, trees, position) || !node.getName().contentEquals(symbolName)) {
      return null;
    }
    String typeFqn = lookupLocalType(localTypes, symbolName);
    if (typeFqn == null) {
      typeFqn = lookupFieldType(ownerFqn, symbolName, core);
    }
    if (typeFqn == null) {
      SymbolInfo symbol = resolveSymbol(fileUri, symbolName, core);
      typeFqn = symbol == null ? null : symbol.getFqName();
    }
    return symbolForTypeFqn(typeFqn, span(toRange(cu, node, trees)), core);
  }

  private SymbolCandidate memberTypeCandidate(
      MemberSelectTree node,
      String ownerFqn,
      String pkg,
      List<String> visibleImports,
      Deque<Map<String, String>> localTypes,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String symbolName,
      CoreQuery core) {
    if (!contains(cu, node, trees, position) || !node.getIdentifier().contentEquals(symbolName)) {
      return null;
    }
    String receiverType =
        expressionType(node.getExpression(), ownerFqn, pkg, visibleImports, localTypes, core);
    if (receiverType == null) {
      return null;
    }
    for (SymbolInfo symbol : core.membersOf(receiverType)) {
      if (symbol.getKind() == SymbolInfo.Kind.FIELD
          && symbolName.equals(memberName(symbol))
          && symbol.getResolvedType() instanceof ClassType classType) {
        return symbolForTypeFqn(classType.fqName(), span(toRange(cu, node, trees)), core);
      }
    }
    return null;
  }

  private SymbolCandidate invocationReturnTypeCandidate(
      MethodInvocationTree node,
      String ownerFqn,
      String pkg,
      List<String> visibleImports,
      Deque<Map<String, String>> localTypes,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String symbolName,
      CoreQuery core) {
    if (!contains(cu, node, trees, position)
        || !Objects.equals(invokedMethodName(node), symbolName)) {
      return null;
    }
    String receiverType =
        receiverType(node.getMethodSelect(), ownerFqn, pkg, visibleImports, localTypes, core);
    if (receiverType == null) {
      return null;
    }
    for (SymbolInfo candidate :
        matchingMethods(receiverType, symbolName, node.getArguments().size(), core)) {
      if (candidate.getMethodSignature() != null
          && candidate.getMethodSignature().returnType() instanceof ClassType classType) {
        return symbolForTypeFqn(classType.fqName(), span(toRange(cu, node, trees)), core);
      }
    }
    return null;
  }

  private SymbolCandidate constructedTypeCandidate(
      NewClassTree node,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String symbolName,
      String pkg,
      List<String> visibleImports,
      CoreQuery core) {
    if (!contains(cu, node, trees, position)) {
      return null;
    }
    String typeFqn = typeFqn(node.getIdentifier(), pkg, visibleImports);
    if (typeFqn == null || !simpleName(typeFqn).equals(symbolName)) {
      return null;
    }
    return symbolForTypeFqn(typeFqn, span(toRange(cu, node, trees)), core);
  }

  private SymbolCandidate symbolForTypeFqn(String typeFqn, int span, CoreQuery core) {
    if (typeFqn == null) {
      return null;
    }
    SymbolInfo symbol = core.findByFqn(typeFqn).orElse(null);
    return symbol == null ? null : new SymbolCandidate(symbol, span);
  }

  private SymbolCandidate classCandidate(
      ClassTree node,
      String ownerFqn,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String symbolName,
      CoreQuery core) {
    if (!contains(cu, node, trees, position) || !node.getSimpleName().contentEquals(symbolName)) {
      return null;
    }
    SymbolInfo symbol = core.findByFqn(ownerFqn).orElse(null);
    return symbol == null ? null : new SymbolCandidate(symbol, span(toRange(cu, node, trees)));
  }

  private SymbolCandidate methodInvocationCandidate(
      MethodTree node,
      String ownerFqn,
      String pkg,
      List<String> visibleImports,
      Deque<Map<String, String>> localTypes,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String symbolName,
      CoreQuery core) {
    return typeCandidate(
        node.getReturnType(), cu, trees, position, symbolName, pkg, visibleImports, core);
  }

  private SymbolCandidate typeCandidate(
      Tree typeTree,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String symbolName,
      String pkg,
      List<String> visibleImports,
      CoreQuery core) {
    if (typeTree == null
        || !contains(cu, typeTree, trees, position)
        || !simpleName(typeTree.toString()).equals(symbolName)) {
      return null;
    }
    String typeFqn = typeFqn(typeTree, pkg, visibleImports);
    if (typeFqn == null) {
      return null;
    }
    SymbolInfo symbol = core.findByFqn(typeFqn).orElse(null);
    return symbol == null ? null : new SymbolCandidate(symbol, span(toRange(cu, typeTree, trees)));
  }

  private SymbolCandidate constructorCandidate(
      NewClassTree node,
      String ownerFqn,
      String pkg,
      List<String> visibleImports,
      Deque<Map<String, String>> localTypes,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String symbolName,
      CoreQuery core) {
    if (!contains(cu, node, trees, position)) {
      return null;
    }
    String typeFqn = typeFqn(node.getIdentifier(), pkg, visibleImports);
    if (typeFqn == null || !simpleName(typeFqn).equals(symbolName)) {
      return null;
    }
    List<SymbolInfo> constructors = matchingConstructors(typeFqn, node.getArguments().size(), core);
    SymbolInfo symbol =
        constructors.isEmpty() ? core.findByFqn(typeFqn).orElse(null) : constructors.getFirst();
    return symbol == null ? null : new SymbolCandidate(symbol, span(toRange(cu, node, trees)));
  }

  private SymbolCandidate invocationCandidate(
      MethodInvocationTree node,
      String ownerFqn,
      String pkg,
      List<String> visibleImports,
      Deque<Map<String, String>> localTypes,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String symbolName,
      CoreQuery core) {
    if (!contains(cu, node, trees, position)) {
      return null;
    }
    String methodName = invokedMethodName(node);
    if (!Objects.equals(methodName, symbolName)) {
      return null;
    }
    String receiverType =
        receiverType(node.getMethodSelect(), ownerFqn, pkg, visibleImports, localTypes, core);
    if (receiverType == null) {
      return null;
    }
    List<SymbolInfo> candidates =
        matchingMethods(receiverType, methodName, node.getArguments().size(), core);
    if (candidates.isEmpty()) {
      return null;
    }
    return new SymbolCandidate(candidates.getFirst(), span(toRange(cu, node, trees)));
  }

  private SymbolCandidate fieldCandidate(
      MemberSelectTree node,
      String ownerFqn,
      String pkg,
      List<String> visibleImports,
      Deque<Map<String, String>> localTypes,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String symbolName,
      CoreQuery core) {
    if (!contains(cu, node, trees, position) || !node.getIdentifier().contentEquals(symbolName)) {
      return null;
    }
    String receiverType =
        expressionType(node.getExpression(), ownerFqn, pkg, visibleImports, localTypes, core);
    if (receiverType == null) {
      return null;
    }
    return core.membersOf(receiverType).stream()
        .filter(symbol -> symbol.getKind() == SymbolInfo.Kind.FIELD)
        .filter(symbol -> symbolName.equals(memberName(symbol)))
        .findFirst()
        .map(symbol -> new SymbolCandidate(symbol, span(toRange(cu, node, trees))))
        .orElse(null);
  }

  private CallSite callSiteForClass(
      ClassTree node,
      String ownerFqn,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String pkg,
      List<String> visibleImports,
      Deque<Map<String, String>> localTypes,
      CoreQuery core) {
    return null;
  }

  private CallSite callSiteForInvocation(
      MethodInvocationTree node,
      String ownerFqn,
      String pkg,
      List<String> visibleImports,
      Deque<Map<String, String>> localTypes,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String content,
      CoreQuery core) {
    if (!contains(cu, node, trees, position) || !isInsideArguments(node, cu, trees, position)) {
      return null;
    }
    String methodName = invokedMethodName(node);
    String receiverType =
        receiverType(node.getMethodSelect(), ownerFqn, pkg, visibleImports, localTypes, core);
    if (methodName == null || receiverType == null) {
      return null;
    }
    List<SymbolInfo> candidates =
        matchingMethods(receiverType, methodName, node.getArguments().size(), core);
    if (candidates.isEmpty()) {
      return null;
    }
    int activeParameter = activeParameterIndex(content, openParenOffset(cu, node, trees), position);
    int activeSignature =
        Math.max(0, candidates.indexOf(bestCallableCandidate(candidates, activeParameter + 1)));
    return new CallSite(
        candidates,
        activeSignature,
        Math.min(activeParameter, Math.max(0, maxParameterCount(candidates) - 1)),
        span(toRange(cu, node, trees)));
  }

  private CallSite callSiteForConstructor(
      NewClassTree node,
      String ownerFqn,
      String pkg,
      List<String> visibleImports,
      Deque<Map<String, String>> localTypes,
      CompilationUnitTree cu,
      Trees trees,
      Position position,
      String content,
      CoreQuery core) {
    if (!contains(cu, node, trees, position) || !isInsideArguments(node, cu, trees, position)) {
      return null;
    }
    String typeFqn = typeFqn(node.getIdentifier(), pkg, visibleImports);
    if (typeFqn == null) {
      return null;
    }
    List<SymbolInfo> candidates = core.constructorsOf(typeFqn);
    if (candidates.isEmpty()) {
      return null;
    }
    int activeParameter = activeParameterIndex(content, openParenOffset(cu, node, trees), position);
    int activeSignature =
        Math.max(0, candidates.indexOf(bestCallableCandidate(candidates, activeParameter + 1)));
    return new CallSite(
        candidates,
        activeSignature,
        Math.min(activeParameter, Math.max(0, maxParameterCount(candidates) - 1)),
        span(toRange(cu, node, trees)));
  }

  private void reportImportReferences(
      String fileUri,
      CompilationUnitTree cu,
      String pkg,
      List<String> visibleImports,
      SymbolReporter reporter,
      Trees trees) {
    for (ImportTree importTree : cu.getImports()) {
      if (importTree.isStatic()) {
        continue;
      }
      String imported = importTree.getQualifiedIdentifier().toString();
      if (imported.endsWith(".*")) {
        continue;
      }
      reporter.reportReference(imported, new Location(fileUri, toRange(cu, importTree, trees)));
    }
  }

  /**
   * Report file-level dependencies by resolving imports and supertypes to workspace file URIs. This
   * populates the DependencyGraph so incremental invalidation works correctly.
   */
  private void reportFileDependencies(
      String fileUri,
      CompilationUnitTree cu,
      String pkg,
      List<String> visibleImports,
      SymbolReporter reporter) {
    CoreQuery core = coreQuery;
    if (core == null) {
      return;
    }
    Set<String> reported = new HashSet<>();
    // Report dependencies from imports
    for (ImportTree importTree : cu.getImports()) {
      String imported = importTree.getQualifiedIdentifier().toString();
      if (imported.endsWith(".*")) {
        continue;
      }
      if (importTree.isStatic()) {
        // Static import: strip member name to get the type FQN
        int lastDot = imported.lastIndexOf('.');
        if (lastDot > 0) {
          imported = imported.substring(0, lastDot);
        }
      }
      reportDependencyForFqn(imported, fileUri, reporter, core, reported);
    }
    // Report dependencies from extends/implements
    Set<String> types = typesByUri.get(fileUri);
    if (types != null) {
      for (String typeFqn : types) {
        List<String> supertypes = directSupertypesByType.get(typeFqn);
        if (supertypes != null) {
          for (String superFqn : supertypes) {
            reportDependencyForFqn(superFqn, fileUri, reporter, core, reported);
          }
        }
      }
    }
  }

  private static void reportDependencyForFqn(
      String fqn, String fileUri, SymbolReporter reporter, CoreQuery core, Set<String> reported) {
    core.findByFqn(fqn)
        .map(SymbolInfo::getLocation)
        .map(Location::getUri)
        .filter(depUri -> !depUri.equals(fileUri))
        .filter(reported::add)
        .ifPresent(reporter::reportDependency);
  }

  private void reportHierarchyReferences(
      String fileUri,
      CompilationUnitTree cu,
      ClassTree node,
      String pkg,
      List<String> visibleImports,
      SymbolReporter reporter,
      Trees trees) {
    if (node.getExtendsClause() != null) {
      reportTypeReference(
          node.getExtendsClause(),
          pkg,
          visibleImports,
          new Location(fileUri, toRange(cu, node.getExtendsClause(), trees)),
          reporter);
    }
    for (Tree implemented : node.getImplementsClause()) {
      reportTypeReference(
          implemented,
          pkg,
          visibleImports,
          new Location(fileUri, toRange(cu, implemented, trees)),
          reporter);
    }
  }

  private void reportTypeReference(
      Tree typeTree,
      String pkg,
      List<String> visibleImports,
      Location location,
      SymbolReporter reporter) {
    String typeFqn = typeFqn(typeTree, pkg, visibleImports);
    if (typeFqn != null) {
      reporter.reportReference(typeFqn, location);
    }
  }

  private void reportConstructorReference(
      String fileUri,
      CompilationUnitTree cu,
      NewClassTree node,
      String callerFqn,
      String pkg,
      List<String> visibleImports,
      Deque<Map<String, String>> localTypes,
      SymbolReporter reporter,
      Trees trees) {
    CoreQuery core = coreQuery;
    if (core == null) {
      return;
    }
    String typeFqn = typeFqn(node.getIdentifier(), pkg, visibleImports);
    if (typeFqn == null) {
      return;
    }
    Location location = new Location(fileUri, toRange(cu, node, trees));
    List<SymbolInfo> constructors = matchingConstructors(typeFqn, node.getArguments().size(), core);
    if (constructors.isEmpty()) {
      reporter.reportReference(typeFqn, location);
      return;
    }
    constructors.forEach(
        symbol -> {
          reporter.reportReference(symbol.getFqName(), location);
          if (callerFqn != null && !callerFqn.isBlank()) {
            reporter.reportCall(callerFqn, symbol.getFqName(), location);
          }
        });
  }

  private void reportMethodReference(
      String fileUri,
      CompilationUnitTree cu,
      MethodInvocationTree node,
      String callerFqn,
      String ownerFqn,
      String pkg,
      List<String> visibleImports,
      Deque<Map<String, String>> localTypes,
      SymbolReporter reporter,
      Trees trees) {
    CoreQuery core = coreQuery;
    if (core == null) {
      return;
    }
    String methodName = invokedMethodName(node);
    String receiverType =
        receiverType(node.getMethodSelect(), ownerFqn, pkg, visibleImports, localTypes, core);
    if (methodName == null || receiverType == null) {
      return;
    }
    Location location = new Location(fileUri, toRange(cu, node, trees));
    for (SymbolInfo candidate :
        matchingMethods(receiverType, methodName, node.getArguments().size(), core)) {
      reporter.reportReference(candidate.getFqName(), location);
      if (callerFqn != null && !callerFqn.isBlank()) {
        reporter.reportCall(callerFqn, candidate.getFqName(), location);
      }
    }
  }

  private void reportExecutableReferences(
      String fileUri,
      CompilationUnitTree cu,
      String pkg,
      List<String> visibleImports,
      SymbolReporter reporter,
      Trees trees) {
    CoreQuery core = coreQuery;
    if (core == null) {
      return;
    }
    cu.accept(
        new TreeScanner<Void, Void>() {
          String owner;
          String callableFqn;
          int methodDepth;
          final Deque<Map<String, String>> localTypes = new ArrayDeque<>();

          @Override
          public Void visitClass(ClassTree node, Void unused) {
            String previousOwner = owner;
            String simple = node.getSimpleName().toString();
            if (!simple.isBlank()) {
              owner = qualifiedTypeName(simple, pkg);
            }
            try {
              return super.visitClass(node, unused);
            } finally {
              owner = previousOwner;
            }
          }

          @Override
          public Void visitMethod(MethodTree node, Void unused) {
            methodDepth++;
            localTypes.push(new LinkedHashMap<>());
            String previousCallable = callableFqn;
            if (owner != null) {
              MethodSignature signature = methodSig(node, pkg, visibleImports);
              callableFqn =
                  node.getReturnType() == null || node.getName().contentEquals("<init>")
                      ? owner + "#<init>" + JvmTypes.toLegacyMethodSignature(signature)
                      : owner + "#" + node.getName() + JvmTypes.toLegacyMethodSignature(signature);
              for (VariableTree parameter : node.getParameters()) {
                String resolvedType = typeFqn(parameter.getType(), pkg, visibleImports);
                if (resolvedType != null) {
                  localTypes.peek().put(parameter.getName().toString(), resolvedType);
                }
              }
            }
            try {
              return super.visitMethod(node, unused);
            } finally {
              callableFqn = previousCallable;
              localTypes.pop();
              methodDepth--;
            }
          }

          @Override
          public Void visitBlock(BlockTree node, Void unused) {
            if (methodDepth > 0) {
              localTypes.push(new LinkedHashMap<>());
            }
            try {
              return super.visitBlock(node, unused);
            } finally {
              if (methodDepth > 0) {
                localTypes.pop();
              }
            }
          }

          @Override
          public Void visitVariable(VariableTree node, Void unused) {
            if (methodDepth > 0 && !localTypes.isEmpty()) {
              String resolvedType = typeFqn(node.getType(), pkg, visibleImports);
              if (resolvedType != null && node.getName() != null) {
                localTypes.peek().put(node.getName().toString(), resolvedType);
              }
            }
            return super.visitVariable(node, unused);
          }

          @Override
          public Void visitNewClass(NewClassTree node, Void unused) {
            reportConstructorReference(
                fileUri, cu, node, callableFqn, pkg, visibleImports, localTypes, reporter, trees);
            return super.visitNewClass(node, unused);
          }

          @Override
          public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            reportMethodReference(
                fileUri,
                cu,
                node,
                callableFqn,
                owner,
                pkg,
                visibleImports,
                localTypes,
                reporter,
                trees);
            return super.visitMethodInvocation(node, unused);
          }
        },
        null);
  }

  private static boolean contains(
      CompilationUnitTree cu, Tree node, Trees trees, Position position) {
    Range range = toRange(cu, node, trees);
    return compare(position, range.start) >= 0 && compare(position, range.end) <= 0;
  }

  private static boolean isInsideArguments(
      MethodInvocationTree node, CompilationUnitTree cu, Trees trees, Position position) {
    int openParen = openParenOffset(cu, node, trees);
    if (openParen < 0) {
      return false;
    }
    long endOffset = trees.getSourcePositions().getEndPosition(cu, node);
    LineMap lineMap = cu.getLineMap();
    Position end =
        new Position(
            (int) lineMap.getLineNumber(endOffset) - 1,
            (int) lineMap.getColumnNumber(endOffset) - 1);
    Position open =
        new Position(
            (int) lineMap.getLineNumber(openParen) - 1,
            (int) lineMap.getColumnNumber(openParen) - 1);
    return compare(position, open) >= 0 && compare(position, end) <= 0;
  }

  private static boolean isInsideArguments(
      NewClassTree node, CompilationUnitTree cu, Trees trees, Position position) {
    int openParen = openParenOffset(cu, node, trees);
    if (openParen < 0) {
      return false;
    }
    long endOffset = trees.getSourcePositions().getEndPosition(cu, node);
    LineMap lineMap = cu.getLineMap();
    Position end =
        new Position(
            (int) lineMap.getLineNumber(endOffset) - 1,
            (int) lineMap.getColumnNumber(endOffset) - 1);
    Position open =
        new Position(
            (int) lineMap.getLineNumber(openParen) - 1,
            (int) lineMap.getColumnNumber(openParen) - 1);
    return compare(position, open) >= 0 && compare(position, end) <= 0;
  }

  private static int compare(Position left, Position right) {
    int line = Integer.compare(left.line, right.line);
    return line != 0 ? line : Integer.compare(left.column, right.column);
  }

  private static int span(Range range) {
    return (range.end.line - range.start.line) * 10_000 + (range.end.column - range.start.column);
  }

  private static int openParenOffset(CompilationUnitTree cu, Tree node, Trees trees) {
    SourcePositions positions = trees.getSourcePositions();
    long start = positions.getStartPosition(cu, node);
    long end = positions.getEndPosition(cu, node);
    try {
      CharSequence source = cu.getSourceFile().getCharContent(true);
      for (int i = (int) start; i < end && i < source.length(); i++) {
        if (source.charAt(i) == '(') {
          return i;
        }
      }
    } catch (IOException ignored) {
      return -1;
    }
    return -1;
  }

  private static int activeParameterIndex(String content, int openParenOffset, Position position) {
    if (openParenOffset < 0) {
      return 0;
    }
    int currentOffset =
        se.alipsa.jvmpls.core.TokenUtil.positionToOffset(content, position.line, position.column);
    int depth = 0;
    int commas = 0;
    for (int i = openParenOffset + 1; i < Math.min(currentOffset, content.length()); i++) {
      char ch = content.charAt(i);
      if (ch == '(') {
        depth++;
      } else if (ch == ')') {
        if (depth == 0) {
          break;
        }
        depth--;
      } else if (ch == ',' && depth == 0) {
        commas++;
      }
    }
    return commas;
  }

  private static String invokedMethodName(MethodInvocationTree node) {
    ExpressionTree methodSelect = node.getMethodSelect();
    if (methodSelect instanceof MemberSelectTree memberSelectTree) {
      return memberSelectTree.getIdentifier().toString();
    }
    if (methodSelect instanceof IdentifierTree identifierTree) {
      return identifierTree.getName().toString();
    }
    return null;
  }

  private String receiverType(
      ExpressionTree methodSelect,
      String ownerFqn,
      String pkg,
      List<String> visibleImports,
      Deque<Map<String, String>> localTypes,
      CoreQuery core) {
    if (methodSelect instanceof MemberSelectTree memberSelectTree) {
      return expressionType(
          memberSelectTree.getExpression(), ownerFqn, pkg, visibleImports, localTypes, core);
    }
    return ownerFqn;
  }

  private String expressionType(
      Tree expression,
      String ownerFqn,
      String pkg,
      List<String> visibleImports,
      Deque<Map<String, String>> localTypes,
      CoreQuery core) {
    if (expression == null) {
      return null;
    }
    if (expression instanceof IdentifierTree identifierTree) {
      String name = identifierTree.getName().toString();
      if ("this".equals(name)) {
        return ownerFqn;
      }
      String local = lookupLocalType(localTypes, name);
      if (local != null) {
        return local;
      }
      String fieldType = lookupFieldType(ownerFqn, name, core);
      if (fieldType != null) {
        return fieldType;
      }
      SymbolInfo type = resolveSymbol(ownerFqn == null ? "" : "file:///ignored", name, core);
      if (type != null && isType(type)) {
        return type.getFqName();
      }
      return qualifiedTypeName(name, pkg);
    }
    if (expression instanceof MemberSelectTree memberSelectTree) {
      String qualifierType =
          expressionType(
              memberSelectTree.getExpression(), ownerFqn, pkg, visibleImports, localTypes, core);
      if (qualifierType == null) {
        return null;
      }
      for (SymbolInfo symbol : core.membersOf(qualifierType)) {
        if (symbol.getKind() == SymbolInfo.Kind.FIELD
            && memberSelectTree.getIdentifier().contentEquals(memberName(symbol))
            && symbol.getResolvedType() instanceof ClassType classType) {
          return classType.fqName();
        }
      }
      return qualifierType;
    }
    if (expression instanceof NewClassTree newClassTree) {
      return typeFqn(newClassTree.getIdentifier(), pkg, visibleImports);
    }
    return null;
  }

  private static String lookupLocalType(Deque<Map<String, String>> localTypes, String name) {
    for (Map<String, String> scope : localTypes) {
      String hit = scope.get(name);
      if (hit != null) {
        return hit;
      }
    }
    return null;
  }

  private static String lookupFieldType(String ownerFqn, String fieldName, CoreQuery core) {
    if (ownerFqn == null || core == null) {
      return null;
    }
    for (SymbolInfo symbol : core.membersOf(ownerFqn)) {
      if (symbol.getKind() == SymbolInfo.Kind.FIELD
          && fieldName.equals(memberName(symbol))
          && symbol.getResolvedType() instanceof ClassType classType) {
        return classType.fqName();
      }
    }
    return null;
  }

  private List<SymbolInfo> matchingMethods(
      String receiverType, String methodName, int argumentCount, CoreQuery core) {
    return core.membersOf(receiverType).stream()
        .filter(symbol -> symbol.getKind() == SymbolInfo.Kind.METHOD)
        .filter(symbol -> methodName.equals(memberName(symbol)))
        .sorted(Comparator.comparingInt(symbol -> arityDistance(symbol, argumentCount)))
        .toList();
  }

  private List<SymbolInfo> matchingConstructors(
      String ownerFqn, int argumentCount, CoreQuery core) {
    return core.constructorsOf(ownerFqn).stream()
        .sorted(Comparator.comparingInt(symbol -> arityDistance(symbol, argumentCount)))
        .toList();
  }

  private static int arityDistance(SymbolInfo symbol, int argumentCount) {
    MethodSignature signature = symbol.getMethodSignature();
    return signature == null
        ? Integer.MAX_VALUE
        : Math.abs(signature.parameterTypes().size() - argumentCount);
  }

  private static SymbolInfo bestCallableCandidate(List<SymbolInfo> candidates, int argumentCount) {
    return candidates.stream()
        .min(Comparator.comparingInt(symbol -> arityDistance(symbol, argumentCount)))
        .orElseGet(() -> candidates.isEmpty() ? null : candidates.getFirst());
  }

  private static int maxParameterCount(List<SymbolInfo> candidates) {
    return candidates.stream()
        .map(SymbolInfo::getMethodSignature)
        .filter(Objects::nonNull)
        .mapToInt(signature -> signature.parameterTypes().size())
        .max()
        .orElse(1);
  }

  private static String renderCallable(SymbolInfo symbol) {
    MethodSignature signature = symbol.getMethodSignature();
    if (signature == null) {
      return symbol.getFqName();
    }
    String name =
        symbol.getKind() == SymbolInfo.Kind.CONSTRUCTOR
            ? simpleName(symbol.getContainerFqName())
            : memberName(symbol);
    StringBuilder builder = new StringBuilder();
    if (symbol.getKind() == SymbolInfo.Kind.METHOD) {
      builder.append(signature.returnType().displayName()).append(' ');
    }
    builder.append(name).append('(');
    for (int i = 0; i < signature.parameterTypes().size(); i++) {
      if (i > 0) {
        builder.append(", ");
      }
      builder.append(signature.parameterTypes().get(i).displayName());
      if (i < signature.parameterNames().size()) {
        builder.append(' ').append(signature.parameterNames().get(i));
      }
    }
    return builder.append(')').toString();
  }

  private static List<String> parameterLabels(SymbolInfo symbol) {
    MethodSignature signature = symbol.getMethodSignature();
    if (signature == null) {
      return List.of();
    }
    ArrayList<String> labels = new ArrayList<>();
    for (int i = 0; i < signature.parameterTypes().size(); i++) {
      String name =
          i < signature.parameterNames().size() ? signature.parameterNames().get(i) : "arg" + i;
      labels.add(signature.parameterTypes().get(i).displayName() + " " + name);
    }
    return List.copyOf(labels);
  }

  private List<Diagnostic> mapCompilerDiagnostics(
      javax.tools.DiagnosticCollector<JavaFileObject> collector) {
    ArrayList<Diagnostic> diagnostics = new ArrayList<>();
    for (javax.tools.Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
      Diagnostic.Severity severity =
          diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR
              ? Diagnostic.Severity.ERROR
              : Diagnostic.Severity.WARNING;
      diagnostics.add(
          new Diagnostic(
              diagnosticRange(diagnostic),
              diagnostic.getMessage(Locale.ROOT),
              severity,
              id(),
              diagnosticCode(diagnostic.getCode(), diagnostic.getMessage(Locale.ROOT))));
    }
    return diagnostics;
  }

  private static Range diagnosticRange(
      javax.tools.Diagnostic<? extends JavaFileObject> diagnostic) {
    long line = Math.max(0, diagnostic.getLineNumber() - 1);
    long column = Math.max(0, diagnostic.getColumnNumber() - 1);
    long endColumn = column + 1;
    return new Range(
        new Position((int) line, (int) column), new Position((int) line, (int) endColumn));
  }

  private static String diagnosticCode(String compilerCode, String message) {
    String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
    if (lower.contains("cannot find symbol")) {
      return "unresolved-symbol";
    }
    if (lower.contains("incompatible types")) {
      return "type-mismatch";
    }
    if (lower.contains("cannot be applied")) {
      return "argument-mismatch";
    }
    if (lower.contains("has private access") || lower.contains("has protected access")) {
      return "access-violation";
    }
    return compilerCode == null ? "javac" : compilerCode;
  }

  private List<CodeActionInfo> autoImportActions(
      String fileUri, String content, Range range, CoreQuery core) {
    Position cursor = range == null ? new Position(0, 0) : range.start;
    String token = completionPrefix(content, cursor);
    if (token.contains(".")) {
      token = token.substring(token.lastIndexOf('.') + 1);
    }
    if (token.isBlank() || !Character.isUpperCase(token.charAt(0))) {
      return List.of();
    }
    if (resolveSymbol(fileUri, token, core) != null) {
      return List.of();
    }
    return core.findBySimpleName(token).stream()
        .filter(JavaPlugin::isType)
        .map(SymbolInfo::getFqName)
        .distinct()
        .map(
            fqn -> {
              List<TextEdit> edits = maybeImportEdit(content, fqn);
              if (edits.isEmpty()) {
                return null;
              }
              return new CodeActionInfo("Import " + fqn, "quickfix", edits, true);
            })
        .filter(Objects::nonNull)
        .toList();
  }

  private Optional<CodeActionInfo> organizeImportsAction(String content) {
    Matcher matcher = IMPORT.matcher(content);
    ArrayList<String> imports = new ArrayList<>();
    int start = -1;
    int end = -1;
    while (matcher.find()) {
      if (start < 0) {
        start = matcher.start();
      }
      end = matcher.end();
      imports.add(matcher.group(1));
    }
    if (imports.isEmpty()) {
      return Optional.empty();
    }
    Set<String> usedTypes = referencedSimpleTypes(content);
    List<String> organized =
        imports.stream()
            .distinct()
            .filter(imp -> imp.endsWith(".*") || usedTypes.contains(simpleName(imp)))
            .sorted()
            .toList();
    String replacement =
        organized.isEmpty()
            ? ""
            : organized.stream().map(imp -> "import " + imp + ";\n").reduce("", String::concat);
    if (replacement.equals(content.substring(start, end))) {
      return Optional.empty();
    }
    Range range = rangeForOffsets(content, start, end);
    return Optional.of(
        new CodeActionInfo(
            "Organize imports",
            "source.organizeImports",
            List.of(new TextEdit(range, replacement)),
            true));
  }

  private static Set<String> referencedSimpleTypes(String content) {
    LinkedHashSet<String> names = new LinkedHashSet<>();
    java.util.regex.Matcher matcher = Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b").matcher(content);
    while (matcher.find()) {
      names.add(matcher.group());
    }
    return Set.copyOf(names);
  }

  private static Range rangeForOffsets(String content, int startOffset, int endOffset) {
    return new Range(positionAtOffset(content, startOffset), positionAtOffset(content, endOffset));
  }

  private static Position positionAtOffset(String content, int offset) {
    int line = 0;
    int column = 0;
    for (int i = 0; i < Math.min(offset, content.length()); i++) {
      if (content.charAt(i) == '\n') {
        line++;
        column = 0;
      } else {
        column++;
      }
    }
    return new Position(line, column);
  }

  private String typeFqn(Tree typeTree, String pkg, List<String> visibleImports) {
    if (typeTree == null) {
      return null;
    }
    JvmType type = resolveType(typeTree.toString(), pkg, visibleImports);
    if (type instanceof ClassType classType) {
      return classType.fqName();
    }
    return null;
  }

  private static String qualifiedTypeName(String simple, String pkg) {
    return (pkg == null || pkg.isBlank()) ? simple : pkg + "." + simple;
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

  private void collectMembersFromReceiver(
      CoreQuery core,
      String receiver,
      String memberPrefix,
      String content,
      java.util.Map<String, CompletionItem> out) {
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
        collectMembersForType(
            classType.fqName(),
            ownerClass,
            classType.fqName(),
            memberPrefix,
            core,
            out,
            new LinkedHashSet<>());
      }
      return;
    }
  }

  private void collectMembersForType(
      String typeFqn,
      String currentOwner,
      String receiverType,
      String memberPrefix,
      CoreQuery core,
      java.util.Map<String, CompletionItem> out,
      Set<String> visitedTypes) {
    if (typeFqn == null || typeFqn.isBlank() || !visitedTypes.add(typeFqn)) {
      return;
    }
    for (SymbolInfo member : core.membersOf(typeFqn)) {
      String name = memberName(member);
      if (name.startsWith(memberPrefix) && isVisible(member, currentOwner, receiverType, core)) {
        addMember(out, member, name);
      }
    }
    for (String supertype : directSupertypesOf(typeFqn, core)) {
      collectMembersForType(
          supertype, currentOwner, receiverType, memberPrefix, core, out, visitedTypes);
    }
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

  // Overloads for add(...) — keep both
  private static void add(java.util.Map<String, CompletionItem> out, SymbolInfo s, String content) {
    String fqn = s.getFqName();
    if (out.containsKey(fqn)) return;
    String simple = simpleName(fqn);
    var edits =
        (content == null)
            ? java.util.List.<se.alipsa.jvmpls.core.model.TextEdit>of()
            : maybeImportEdit(content, fqn); // your auto-import builder
    out.put(fqn, new CompletionItem(simple, fqn, simple, s.getLocation(), edits));
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
    out.put(key, new CompletionItem(label, detail, label, s.getLocation(), List.of(), typeDetail));
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD")
  private static void add(java.util.Map<String, CompletionItem> out, SymbolInfo s) {
    add(out, s, null);
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
      if (c == '\n') {
        line++;
        col = 0;
      } else {
        col++;
      }
    }
    Range r = new Range(new Position(line, col), new Position(line, col));
    String sep = (insertAt == 0) ? "" : (content.charAt(insertAt - 1) == '\n' ? "" : "\n");
    String text = sep + "import " + fqn + ";\n";
    return java.util.List.of(new TextEdit(r, text));
  }

  private static List<String> visibleImports(CompilationUnitTree cu) {
    List<String> imports =
        new ArrayList<>(JAVA_DEFAULT_STAR_IMPORTS.stream().map(pkg -> pkg + ".*").toList());
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
      return JvmTypes.fromSource(
          rawType, simpleName -> fallbackResolveImportedTypeName(simpleName, pkg, visibleImports));
    }
    return JvmTypes.fromSource(
        rawType,
        simpleName -> {
          String resolved = resolver.resolveClassName(simpleName, pkg, visibleImports);
          if (!Objects.equals(resolved, simpleName)) {
            return resolved;
          }
          return fallbackResolveImportedTypeName(simpleName, pkg, visibleImports);
        });
  }

  private static String fallbackResolveImportedTypeName(
      String simpleName, String pkg, List<String> visibleImports) {
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
        String candidate =
            visibleImport.substring(0, visibleImport.length() - 2) + "." + simpleName;
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
    LinkedHashSet<String> modifiers =
        flags.stream()
            .map(flag -> flag.name().toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    if (!modifiers.contains("public")
        && !modifiers.contains("protected")
        && !modifiers.contains("private")) {
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

  private boolean isVisible(
      SymbolInfo member, String currentOwner, String receiverType, CoreQuery core) {
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
          || (isSubtypeOrSame(currentOwner, member.getContainerFqName(), core)
              && isSubtypeOrSame(receiverType, currentOwner, core));
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
      String fileUri, String typeFqn, ClassTree node, String pkg, List<String> visibleImports) {
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
}
