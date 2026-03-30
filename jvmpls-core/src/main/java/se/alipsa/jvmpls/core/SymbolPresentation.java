package se.alipsa.jvmpls.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import se.alipsa.jvmpls.core.model.CallableInfo;
import se.alipsa.jvmpls.core.model.HoverInfo;
import se.alipsa.jvmpls.core.model.SymbolInfo;
import se.alipsa.jvmpls.core.types.MethodSignature;

final class SymbolPresentation {

  private SymbolPresentation() {}

  static HoverInfo hover(SymbolInfo symbol, String documentation) {
    return new HoverInfo(
        title(symbol), detail(symbol), documentation, provenance(symbol), symbol.getLocation());
  }

  static CallableInfo callable(SymbolInfo symbol, String documentation) {
    MethodSignature signature = symbol.getMethodSignature();
    List<String> parameterLabels = new ArrayList<>();
    if (signature != null) {
      for (int i = 0; i < signature.parameterTypes().size(); i++) {
        String parameterType = signature.parameterTypes().get(i).displayName();
        String parameterName =
            i < signature.parameterNames().size() ? signature.parameterNames().get(i) : "arg" + i;
        parameterLabels.add(parameterType + " " + parameterName);
      }
    }
    return new CallableInfo(detail(symbol), parameterLabels, documentation, symbol.getLocation());
  }

  static String simpleName(SymbolInfo symbol) {
    String fqn = symbol.getFqName();
    return switch (symbol.getKind()) {
      case FIELD -> {
        int lastDot = fqn.lastIndexOf('.');
        yield lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
      }
      case METHOD -> {
        int hash = fqn.lastIndexOf('#');
        int open = fqn.indexOf('(', hash + 1);
        yield open < 0 ? fqn.substring(hash + 1) : fqn.substring(hash + 1, open);
      }
      case CONSTRUCTOR -> {
        String owner = symbol.getContainerFqName();
        int lastDot = owner.lastIndexOf('.');
        yield lastDot < 0 ? owner : owner.substring(lastDot + 1);
      }
      default -> {
        int lastDot = fqn.lastIndexOf('.');
        yield lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
      }
    };
  }

  static Comparator<SymbolInfo> locationOrder() {
    return Comparator.comparing(
            (SymbolInfo symbol) ->
                symbol.getLocation() == null ? "" : symbol.getLocation().getUri())
        .thenComparingInt(
            symbol ->
                symbol.getLocation() == null
                    ? Integer.MAX_VALUE
                    : symbol.getLocation().getRange().start.line)
        .thenComparingInt(
            symbol ->
                symbol.getLocation() == null
                    ? Integer.MAX_VALUE
                    : symbol.getLocation().getRange().start.column)
        .thenComparing(SymbolInfo::getFqName);
  }

  private static String title(SymbolInfo symbol) {
    return switch (symbol.getKind()) {
      case CLASS -> "class " + simpleName(symbol);
      case INTERFACE -> "interface " + simpleName(symbol);
      case ENUM -> "enum " + simpleName(symbol);
      case ANNOTATION -> "@interface " + simpleName(symbol);
      case FIELD ->
          (symbol.getResolvedType() == null
                  ? symbol.getSignature()
                  : symbol.getResolvedType().displayName())
              + " "
              + simpleName(symbol);
      case METHOD, CONSTRUCTOR -> detail(symbol);
      case PACKAGE -> "package " + symbol.getFqName();
    };
  }

  private static String detail(SymbolInfo symbol) {
    return switch (symbol.getKind()) {
      case METHOD -> renderMethod(symbol, simpleName(symbol));
      case CONSTRUCTOR -> renderMethod(symbol, simpleName(symbol));
      case FIELD -> symbol.getContainerFqName();
      default -> symbol.getFqName();
    };
  }

  private static String renderMethod(SymbolInfo symbol, String name) {
    MethodSignature signature = symbol.getMethodSignature();
    if (signature == null) {
      return symbol.getFqName();
    }
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

  private static String provenance(SymbolInfo symbol) {
    if (symbol.getSyntheticOrigin() == null
        || symbol.getSyntheticOrigin() == se.alipsa.jvmpls.core.model.SyntheticOrigin.NONE) {
      return "";
    }
    String source = symbol.getSyntheticOrigin().name().toLowerCase().replace('_', ' ');
    String confidence =
        symbol.getInferenceConfidence() == null
            ? ""
            : " (" + symbol.getInferenceConfidence().name().toLowerCase() + " confidence)";
    return "inferred from " + source + confidence;
  }
}
