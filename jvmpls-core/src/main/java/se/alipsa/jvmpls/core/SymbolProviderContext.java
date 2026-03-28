package se.alipsa.jvmpls.core;

import java.nio.file.Path;
import java.util.List;

/**
 * Context passed to {@link SymbolProviderFactory} implementations.
 */
public record SymbolProviderContext(List<String> classpathEntries, Path targetJdkHome) {

  public SymbolProviderContext {
    classpathEntries = classpathEntries == null ? List.of() : List.copyOf(classpathEntries);
  }
}
