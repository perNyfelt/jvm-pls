package se.alipsa.jvmpls.classpath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ScannedTypeCatalog {

  private final Map<String, ScannedTypeDescriptor> byFqn;
  private final Map<String, List<ScannedTypeDescriptor>> bySimpleName;
  private final Map<String, List<ScannedTypeDescriptor>> byPackage;

  private ScannedTypeCatalog(Map<String, ScannedTypeDescriptor> byFqn,
                             Map<String, List<ScannedTypeDescriptor>> bySimpleName,
                             Map<String, List<ScannedTypeDescriptor>> byPackage) {
    this.byFqn = byFqn;
    this.bySimpleName = bySimpleName;
    this.byPackage = byPackage;
  }

  static Builder builder() {
    return new Builder();
  }

  Optional<ScannedTypeDescriptor> findByFqn(String fqn) {
    return Optional.ofNullable(byFqn.get(fqn));
  }

  List<ScannedTypeDescriptor> findBySimpleName(String simpleName) {
    return bySimpleName.getOrDefault(simpleName, List.of());
  }

  List<ScannedTypeDescriptor> allInPackage(String pkg) {
    return byPackage.getOrDefault(pkg, List.of());
  }

  boolean isEmpty() {
    return byFqn.isEmpty();
  }

  static final class Builder {

    private final Map<String, ScannedTypeDescriptor> byFqn = new LinkedHashMap<>();
    private final Map<String, List<ScannedTypeDescriptor>> bySimpleName = new LinkedHashMap<>();
    private final Map<String, List<ScannedTypeDescriptor>> byPackage = new LinkedHashMap<>();

    Builder add(ScannedTypeDescriptor descriptor) {
      if (descriptor == null || descriptor.fqName() == null || descriptor.fqName().isBlank()) {
        return this;
      }
      if (byFqn.putIfAbsent(descriptor.fqName(), descriptor) == null) {
        bySimpleName.computeIfAbsent(descriptor.simpleName(), key -> new ArrayList<>()).add(descriptor);
        byPackage.computeIfAbsent(descriptor.packageName(), key -> new ArrayList<>()).add(descriptor);
      }
      return this;
    }

    Builder merge(ScannedTypeCatalog catalog) {
      if (catalog == null) {
        return this;
      }
      catalog.byFqn.values().forEach(this::add);
      return this;
    }

    ScannedTypeCatalog build() {
      return new ScannedTypeCatalog(
          Map.copyOf(byFqn),
          copyListMap(bySimpleName),
          copyListMap(byPackage));
    }

    private static Map<String, List<ScannedTypeDescriptor>> copyListMap(
        Map<String, List<ScannedTypeDescriptor>> source) {
      Map<String, List<ScannedTypeDescriptor>> copy = new LinkedHashMap<>();
      source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
      return Map.copyOf(copy);
    }
  }
}
