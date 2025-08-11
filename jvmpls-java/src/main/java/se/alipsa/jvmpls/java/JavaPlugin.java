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

public final class JavaPlugin implements JvmLangPlugin {

  private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();
  private final Map<String,String> contentByUri = new ConcurrentHashMap<>();

  private static final java.util.regex.Pattern PKG =
      java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
  private static final java.util.regex.Pattern IMPORT =
      java.util.regex.Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)\\s*;");
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
      var options = List.of("-proc:none", "-source", "21");
      JavacTask task = (JavacTask) COMPILER.getTask(null, fm, null, options, null, List.of(mem));
      Trees trees = Trees.instance(task);

      for (CompilationUnitTree cu : task.parse()) {
        String pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
        if (!pkg.isEmpty()) {
          reporter.reportPackage(pkg, new Location(fileUri, new Range(new Position(0,0), new Position(0,1))));
        }

        cu.accept(new TreeScanner<Void, Void>() {
          String owner;

          @Override public Void visitClass(ClassTree node, Void p) {
            String simple = node.getSimpleName().toString();
            if (!simple.isEmpty()) {
              String fqn = (pkg.isEmpty() ? "" : pkg + ".") + simple;
              boolean isInterface = node.getKind() == Tree.Kind.INTERFACE;
              boolean isEnum      = node.getKind() == Tree.Kind.ENUM;
              boolean isAnno      = node.getKind() == Tree.Kind.ANNOTATION_TYPE;

              reporter.reportClass(fqn, new Location(fileUri, toRange(cu, node, trees)), isInterface, isEnum, isAnno);
              owner = fqn;
            }
            return super.visitClass(node, p);
          }

          @Override public Void visitMethod(MethodTree node, Void p) {
            if (owner != null) {
              reporter.reportMethod(owner, node.getName().toString(), methodSig(node),
                  new Location(fileUri, toRange(cu, node, trees)));
            }
            return null;
          }

          @Override public Void visitVariable(VariableTree node, Void p) {
            if (owner != null && node.getName() != null) {
              String type = node.getType() == null ? "java.lang.Object" : node.getType().toString();
              reporter.reportField(owner, node.getName().toString(), type,
                  new Location(fileUri, toRange(cu, node, trees)));
            }
            return null;
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
    if (symbolName.indexOf('.') >= 0) {
      return core.findByFqn(symbolName).orElse(null);
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

    // Dotted prefix => treat as explicit package prefix
    int lastDot = prefix.lastIndexOf('.');
    if (lastDot >= 0) {
      String pkg = prefix.substring(0, lastDot);
      String simplePrefix = prefix.substring(lastDot + 1);
      collectTypesFromPackage(core, pkg, simplePrefix, out);
      return List.copyOf(out.values());
    }

    // Undotted: same package + imports + defaults
    String pkg = find(PKG, content); // you already have PKG & IMPORT patterns
    if (pkg != null && !pkg.isBlank()) {
      collectTypesFromPackage(core, pkg, prefix, out);
    }

    // imports
    var m = IMPORT.matcher(content);
    while (m.find()) {
      String imp = m.group(1);
      if (imp.endsWith(".*")) {
        collectTypesFromPackage(core, imp.substring(0, imp.length() - 2), prefix, out);
      } else {
        core.findByFqn(imp).ifPresent(sym -> {
          if (isType(sym) && simpleName(sym.getFqName()).startsWith(prefix)) add(out, sym);
        });
      }
    }

    // defaults
    for (String p : JAVA_DEFAULT_STAR_IMPORTS) {
      collectTypesFromPackage(core, p, prefix, out);
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

  private static void add(java.util.Map<String, CompletionItem> out, SymbolInfo s) {
    String fqn = s.getFqName();
    if (out.containsKey(fqn)) return;
    String simple = simpleName(fqn);
    out.put(fqn, new CompletionItem(
        simple,          // label
        fqn,             // detail
        simple,          // insertText
        s.getLocation()  // location (optional but nice to have)
    ));
  }

  private static void collectTypesFromPackage(CoreQuery core, String pkg, String simplePrefix,
                                              java.util.Map<String, CompletionItem> out) {
    for (var s : core.allInPackage(pkg)) {
      if (isType(s) && simpleName(s.getFqName()).startsWith(simplePrefix)) {
        add(out, s);
      }
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

}
