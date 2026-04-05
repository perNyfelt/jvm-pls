package se.alipsa.jvmpls.core;

import java.nio.file.Path;
import java.util.List;

/** Context passed to {@link SymbolProviderFactory} implementations. */
public record SymbolProviderContext(
    List<String> classpathEntries, Path targetJdkHome, Path workspaceRoot) {

  public SymbolProviderContext {
    classpathEntries = classpathEntries == null ? List.of() : List.copyOf(classpathEntries);
  }

  /** Backward-compatible constructor for callers that don't specify a workspace root. */
  public SymbolProviderContext(List<String> classpathEntries, Path targetJdkHome) {
    this(classpathEntries, targetJdkHome, null);
  }
}
