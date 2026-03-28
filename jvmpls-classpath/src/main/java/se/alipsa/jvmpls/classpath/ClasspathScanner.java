package se.alipsa.jvmpls.classpath;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import se.alipsa.jvmpls.core.model.SymbolInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class ClasspathScanner {

  public ScannedTypeCatalog scan(List<String> classpathEntries) {
    List<String> normalized = classpathEntries == null ? List.of() : classpathEntries.stream()
        .filter(entry -> entry != null && !entry.isBlank())
        .filter(entry -> Files.exists(Path.of(entry)))
        .distinct()
        .collect(Collectors.toList());
    if (normalized.isEmpty()) {
      return ScannedTypeCatalog.builder().build();
    }

    ClassGraph classGraph = new ClassGraph()
        .enableClassInfo()
        .ignoreClassVisibility()
        .overrideClasspath(normalized);

    ScannedTypeCatalog.Builder builder = ScannedTypeCatalog.builder();
    try (ScanResult scanResult = classGraph.scan()) {
      for (ClassInfo classInfo : scanResult.getAllClasses()) {
        if (classInfo.isAnonymousInnerClass()) {
          continue;
        }
        String resourceUri = classInfo.getResource() != null
            ? classInfo.getResource().getURI().toString()
            : null;
        if (resourceUri == null) {
          continue;
        }
        builder.add(new ScannedTypeDescriptor(
            classInfo.getName(),
            classInfo.getPackageName(),
            containerFqName(classInfo.getName()),
            kindOf(classInfo),
            resourceUri,
            classInfo.getSuperclass() == null ? null : classInfo.getSuperclass().getName(),
            classInfo.getInterfaces().getNames()));
      }
    }
    return builder.build();
  }

  private static SymbolInfo.Kind kindOf(ClassInfo classInfo) {
    if (classInfo.isAnnotation()) {
      return SymbolInfo.Kind.ANNOTATION;
    }
    if (classInfo.isInterface()) {
      return SymbolInfo.Kind.INTERFACE;
    }
    if (classInfo.isEnum()) {
      return SymbolInfo.Kind.ENUM;
    }
    return SymbolInfo.Kind.CLASS;
  }

  private static String containerFqName(String fqName) {
    int lastDot = fqName.lastIndexOf('.');
    return lastDot < 0 ? "" : fqName.substring(0, lastDot);
  }
}
