package se.alipsa.jvmpls.core.types;

import se.alipsa.jvmpls.core.CoreQuery;

import java.util.List;
import java.util.Objects;

public final class TypeResolver {

  private final CoreQuery core;

  public TypeResolver(CoreQuery core) {
    this.core = Objects.requireNonNull(core, "core");
  }

  public JvmType resolveSourceType(String rawType, String packageName, List<String> visibleImports) {
    return JvmTypes.fromSource(rawType, simpleName -> resolveClassName(simpleName, packageName, visibleImports));
  }

  public String resolveClassName(String simpleName, String packageName, List<String> visibleImports) {
    if (simpleName == null || simpleName.isBlank() || simpleName.contains(".")) {
      return simpleName;
    }
    if (JvmTypes.isPrimitive(simpleName) || "void".equals(simpleName)) {
      return simpleName;
    }
    if (visibleImports != null) {
      for (String visibleImport : visibleImports) {
        if (visibleImport.endsWith(".*")) {
          String candidate = visibleImport.substring(0, visibleImport.length() - 2) + "." + simpleName;
          if (core.findByFqn(candidate).isPresent()) {
            return candidate;
          }
        } else if (visibleImport.endsWith("." + simpleName)) {
          return visibleImport;
        }
      }
    }
    if (packageName != null && !packageName.isBlank()) {
      String samePackage = packageName + "." + simpleName;
      if (core.findByFqn(samePackage).isPresent()) {
        return samePackage;
      }
    }
    if (core.findByFqn("java.lang." + simpleName).isPresent()) {
      return "java.lang." + simpleName;
    }
    return core.findBySimpleName(simpleName).stream()
        .findFirst()
        .map(symbol -> symbol.getFqName())
        .orElse(simpleName);
  }
}
