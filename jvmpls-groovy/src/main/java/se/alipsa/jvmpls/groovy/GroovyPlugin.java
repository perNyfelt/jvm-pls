package se.alipsa.jvmpls.groovy;

import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.JvmLangPlugin;
import se.alipsa.jvmpls.core.SymbolReporter;
import se.alipsa.jvmpls.core.model.*;

import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.builder.AstBuilder;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class GroovyPlugin implements JvmLangPlugin {

  @Override public String id() { return "groovy"; }

  @Override public Set<String> fileExtensions() { return Set.of("groovy", "gvy", "gy", "gsh"); }

  private static final java.util.regex.Pattern PKG_DECL =
      java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;?\\s*$");
  private static final java.util.regex.Pattern IMPORT_LINE =
      java.util.regex.Pattern.compile("(?m)^\\s*import(?:\\s+static)?\\s+([\\w.]+)(?:\\s+as\\s+\\w+)?\\s*$");
  private static final java.util.regex.Pattern STAR_SUFFIX =
      java.util.regex.Pattern.compile("\\.\\*$");

  // Keep per-file context for resolveSymbol (package & imports)
  private static final class FileCtx {
    String pkg = "";
    final List<String> singleImports = new ArrayList<>(); // a.b.C
    final List<String> starImports   = new ArrayList<>(); // a.b (package)
  }
  private final Map<String, FileCtx> ctxByUri = new ConcurrentHashMap<>();
  private final Map<String, String>  contentByUri = new ConcurrentHashMap<>();

  // Groovy default star imports
  private static final List<String> DEFAULT_STAR_IMPORTS = List.of(
      "java.lang", "groovy.lang", "groovy.util"
  );

  @Override
  public List<Diagnostic> index(String fileUri, String content, SymbolReporter reporter) {
    contentByUri.put(fileUri, content);
    var diags = new ArrayList<Diagnostic>();
    var fileCtx = new FileCtx();

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
            if (cls != null && !cls.isBlank()) fileCtx.singleImports.add(cls);
          }

          // static single imports: import static a.b.C.D
          for (ImportNode imp : mn.getStaticImports().values()) {
            String cls = imp.getClassName();
            if (cls != null && !cls.isBlank()) fileCtx.singleImports.add(cls);
          }

          // star imports (import a.b.*)
          for (ImportNode imp : mn.getStarImports()) {
            String pkg = imp.getPackageName();
            if (pkg != null && !pkg.isBlank()) fileCtx.starImports.add(trimDot(pkg));
          }

          // static star imports (import static a.b.C.*) -> treat as class owner’s package for simple lookup
          for (ImportNode imp : mn.getStaticStarImports().values()) {
            String cls = imp.getClassName();
            if (cls != null) {
              int i = cls.lastIndexOf('.');
              if (i > 0) fileCtx.starImports.add(cls.substring(0, i));
            }
          }

          // report package symbol (best-effort)
          if (!fileCtx.pkg.isBlank()) {
            reporter.reportPackage(fileCtx.pkg, new Location(fileUri,
                new Range(new Position(0,0), new Position(0,1))));
          }

          // visit classes under this module
          for (ClassNode cn : mn.getClasses()) {
            visitClass(fileUri, cn, reporter, fileCtx); // pass ctx
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
              new Range(new Position(0,0), new Position(0,1)),
              msg.toString(), Diagnostic.Severity.ERROR, id(), "error"));
        }
      }
    } catch (Throwable t) {
      diags.add(new Diagnostic(new Range(new Position(0,0), new Position(0,1)),
          "Parse error: " + t.getMessage(), Diagnostic.Severity.ERROR, id(), "parse"));
    }

    // Save ctx after successful/attempted parse (best-effort for resolveSymbol)
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

    // FQN direct lookup
    if (symbolName.indexOf('.') >= 0) {
      return core.findByFqn(symbolName).orElse(null);
    }

    FileCtx ctx = ctxByUri.getOrDefault(fileUri, new FileCtx());

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

    String prefix = completionPrefix(content, position); // may include dots
    var out = new java.util.LinkedHashMap<String, CompletionItem>(); // fqName -> item

    // Dotted prefix => explicit package
    int lastDot = prefix.lastIndexOf('.');
    if (lastDot >= 0) {
      String pkg = prefix.substring(0, lastDot);
      String simplePrefix = prefix.substring(lastDot + 1);
      collectTypesFromPackage(core, pkg, simplePrefix, out);
      return List.copyOf(out.values());
    }

    // Load ctx (and ensure package fallback)
    FileCtx ctx = ctxByUri.getOrDefault(fileUri, new FileCtx());
    if (ctx.pkg == null || ctx.pkg.isBlank()) {
      var m = PKG_DECL.matcher(content);
      if (m.find()) { ctx.pkg = m.group(1); ctxByUri.put(fileUri, ctx); }
    }

    // If ctx imports are empty (e.g., parse failed earlier), recover from raw text
    if (ctx.singleImports.isEmpty() && ctx.starImports.isEmpty()) {
      var m = IMPORT_LINE.matcher(content);
      while (m.find()) {
        String imp = m.group(1);
        if (STAR_SUFFIX.matcher(imp).find()) {
          ctx.starImports.add(imp.substring(0, imp.length() - 2));
        } else {
          ctx.singleImports.add(imp);
        }
      }
    }

    // Same package
    if (ctx.pkg != null && !ctx.pkg.isBlank()) {
      collectTypesFromPackage(core, ctx.pkg, prefix, out);
    }

    // Single imports
    for (String imp : ctx.singleImports) {
      core.findByFqn(imp).ifPresent(sym -> {
        if (isType(sym) && simpleName(sym.getFqName()).startsWith(prefix)) add(out, sym);
      });
    }

    // Star imports
    for (String p : union(ctx.starImports, DEFAULT_STAR_IMPORTS)) {
      collectTypesFromPackage(core, p, prefix, out);
    }

    return List.copyOf(out.values());
  }


  @Override public void forget(String fileUri) {
    ctxByUri.remove(fileUri);
    contentByUri.remove(fileUri);
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

    // Methods
    for (MethodNode mn : cn.getMethods()) {
      if (mn.getDeclaringClass() != cn) continue;
      reporter.reportMethod(fqn, mn.getName(), methodSig(mn), new Location(fileUri, toRange(mn)));
    }
    // Fields
    for (FieldNode fn : cn.getFields()) {
      if (fn.getDeclaringClass() != cn) continue;
      reporter.reportField(fqn, fn.getName(), typeName(fn.getType()), new Location(fileUri, toRange(fn)));
    }
    // Groovy properties as fields
    for (PropertyNode pn : cn.getProperties()) {
      reporter.reportField(fqn, pn.getName(), typeName(pn.getType()), new Location(fileUri, toRange(pn)));
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

  private static String methodSig(MethodNode mn) {
    StringBuilder sb = new StringBuilder("(");
    boolean first = true;
    for (Parameter p : mn.getParameters()) {
      if (!first) sb.append(",");
      sb.append(typeName(p.getType()));
      first = false;
    }
    sb.append(")");
    return sb + typeName(mn.getReturnType());
  }

  private static String typeName(ClassNode t) {
    return t == null ? "java.lang.Object" : t.getName();
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

  private static void add(java.util.Map<String, CompletionItem> out, SymbolInfo s) {
    String fqn = s.getFqName();
    if (out.containsKey(fqn)) return;
    String simple = simpleName(fqn);
    out.put(fqn, new CompletionItem(simple, fqn, simple, s.getLocation()));
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
