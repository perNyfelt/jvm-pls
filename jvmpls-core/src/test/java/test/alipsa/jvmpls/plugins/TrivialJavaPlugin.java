package test.alipsa.jvmpls.plugins;

import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.JvmLangPlugin;
import se.alipsa.jvmpls.core.SymbolReporter;
import se.alipsa.jvmpls.core.model.*;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TrivialJavaPlugin implements JvmLangPlugin {

  private static final Pattern PKG = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
  private static final Pattern CLS = Pattern.compile("(?m)^\\s*(?:public\\s+)?class\\s+([A-Za-z_]\\w*)\\b");

  @Override public String id() { return "trivial-java"; }
  @Override public Set<String> fileExtensions() { return Set.of("java"); }

  @Override
  public List<Diagnostic> index(String fileUri, String content, SymbolReporter reporter) {
    String pkg = find(PKG, content);
    String cls = find(CLS, content);
    if (cls == null) return List.of();

    String fqn = (pkg == null || pkg.isEmpty()) ? cls : pkg + "." + cls;

    // super rough ranges: mark the first character of the 'class' line
    int clsPos = content.indexOf("class " + cls);
    Range r = (clsPos < 0) ? new Range(new Position(0,0), new Position(0,1))
        : toRange(content, clsPos, ("class " + cls).length());

    if (pkg != null && !pkg.isEmpty()) {
      reporter.reportPackage(pkg, new Location(fileUri, new Range(new Position(0,0), new Position(0,1))));
    }
    reporter.reportClass(fqn, new Location(fileUri, r), false, false, false);
    return List.of(); // no diagnostics for the smoke test
  }

  @Override
  public SymbolInfo resolveSymbol(String fileUri, String symbolName, CoreQuery core) {
    // Very naive: if user asks for the simple name or FQN, return the class we just reported
    return core.findByFqn(symbolName).orElseGet(() -> {
      // try to find any class in the same package with that simple name
      // (skip for simplicity in this trivial plugin)
      return null;
    });
  }

  private static String find(Pattern p, String s) {
    Matcher m = p.matcher(s);
    return m.find() ? m.group(1) : null;
  }

  private static Range toRange(String text, int startOffset, int length) {
    int line = 0, col = 0, pos = 0;
    for (; pos < startOffset && pos < text.length(); pos++) {
      char c = text.charAt(pos);
      if (c == '\n') { line++; col = 0; } else { col++; }
    }
    Position start = new Position(line, col);
    for (int i = 0; i < length && pos < text.length(); i++, pos++) {
      if (text.charAt(pos) == '\n') { line++; col = 0; } else { col++; }
    }
    Position end = new Position(line, col);
    return new Range(start, end);
  }
}
