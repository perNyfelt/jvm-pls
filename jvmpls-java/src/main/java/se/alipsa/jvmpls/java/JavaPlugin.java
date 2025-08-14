package se.alipsa.jvmpls.java;

import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.JvmLangPlugin;
import se.alipsa.jvmpls.core.SymbolReporter;
import se.alipsa.jvmpls.core.model.*;

import javax.tools.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import se.alipsa.jvmpls.core.model.Diagnostic;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class JavaPlugin implements JvmLangPlugin {

  private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();
  private final Map<String,String> contentByUri = new ConcurrentHashMap<>();

  private static final java.util.regex.Pattern PKG =
      Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
  private static final java.util.regex.Pattern IMPORT =
      // Capture both single-type and on-demand imports:
      // group(1) examples: "a.b.C", "a.b.*"
      Pattern.compile("(?m)^\\s*import(?:\\s+static)?\\s+([\\w.]+(?:\\.\\*)?)\\s*;");
  private static final List<String> JAVA_DEFAULT_STAR_IMPORTS = List.of("java.lang");

  @Override public String id() { return "java"; }
  @Override public Set<String> fileExtensions() { return Set.of("java"); }

  @Override
  public List<Diagnostic> index(String fileUri, String content, SymbolReporter reporter) {
    contentByUri.put(fileUri, content);
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
        if (!pkg.isEmpty()) {
          reporter.reportPackage(pkg, new Location(fileUri, new Range(new Position(0, 0), new Position(0, 1))));
        }

        cu.accept(new TreeScanner<Void, Void>() {
          String owner; // current enclosing FQN

          @Override public Void visitClass(ClassTree node, Void p) {
            String simple = node.getSimpleName().toString();
            if (!simple.isEmpty()) {
              String fqn = (pkg.isEmpty() ? "" : pkg + ".") + simple;
              boolean isInterface = node.getKind() == Tree.Kind.INTERFACE;
              boolean isEnum      = node.getKind() == Tree.Kind.ENUM;
              boolean isAnno      = node.getKind() == Tree.Kind.ANNOTATION_TYPE;

              reporter.reportClass(fqn, new Location(fileUri, toRange(cu, node, trees)), isInterface, isEnum, isAnno);

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
            if (owner != null) {
              reporter.reportMethod(owner, node.getName().toString(), methodSig(node),
                  new Location(fileUri, toRange(cu, node, trees)));
            }
            return super.visitMethod(node, p);
          }

          @Override public Void visitVariable(VariableTree node, Void p) {
            if (owner != null && node.getName() != null) {
              String type = (node.getType() == null) ? "java.lang.Object" : node.getType().toString();
              reporter.reportField(owner, node.getName().toString(), type,
                  new Location(fileUri, toRange(cu, node, trees)));
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

  @Override public void forget(String fileUri) { contentByUri.remove(fileUri); }

  @Override
  public List<CompletionItem> completions(String fileUri, Position position, CoreQuery core) {
    String content = contentByUri.get(fileUri);
    if (content == null) return List.of();

    String prefix = completionPrefix(content, position); // may include dots
    var out = new java.util.LinkedHashMap<String, CompletionItem>(); // fqName -> item

    // 1) Dotted prefix => collect by explicit package
    int lastDot = prefix.lastIndexOf('.');
    if (lastDot >= 0) {
      String pkg = prefix.substring(0, lastDot);
      String simplePrefix = prefix.substring(lastDot + 1);
      collectTypesFromPackage(core, pkg, simplePrefix, content, out);
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

  private static String methodSig(MethodTree mt) {
    StringBuilder sb = new StringBuilder("(");
    boolean first = true;
    for (var p : mt.getParameters()) {
      if (!first) sb.append(",");
      sb.append(p.getType() == null ? "java.lang.Object" : p.getType().toString());
      first = false;
    }
    sb.append(")");
    String ret = mt.getReturnType() == null ? "void" : mt.getReturnType().toString();
    return sb + ret;
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

  private static boolean isType(SymbolInfo s) {
    return switch (s.getKind()) {
      case CLASS, INTERFACE, ENUM, ANNOTATION -> true;
      default -> false;
    };
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

}
