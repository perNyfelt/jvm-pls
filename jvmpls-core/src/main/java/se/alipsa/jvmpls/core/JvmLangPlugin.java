package se.alipsa.jvmpls.core;

import se.alipsa.jvmpls.core.model.*;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public interface JvmLangPlugin {

  /** Unique, stable identifier, e.g. "java", "groovy", "kotlin". */
  String id();

  /** Human-friendly name, e.g. "Java", "Groovy". */
  default String displayName() { return id(); }

  /** File extensions (lowercase, no dot), e.g. ["java"]. */
  Set<String> fileExtensions();

  /**
   * Claim how confident you are that you handle this file. 0.0 = not mine, 1.0 = certainly mine.
   * Core calls this when it needs to choose a plugin. Use URI and a cheap content peek.
   */
  default double claim(String fileUri, Supplier<CharSequence> contentPreview) {
    String ext = fileUri.contains(".") ? fileUri.substring(fileUri.lastIndexOf('.') + 1).toLowerCase() : "";
    return fileExtensions().contains(ext) ? 0.9 : 0.0; // extensions win by default
  }

  /** Called once after registration; plugins can cache references to core services. */
  default void configure(PluginEnvironment env) {}

  /** Parse/analyze and report declarations for indexing. Return diagnostics. */
  List<Diagnostic> index(String fileUri, String content, SymbolReporter reporter);

  /** Attempt to resolve a symbol name in file context to a known symbol. */
  default SymbolInfo resolveSymbol(String fileUri, String symbolName, CoreQuery core) { return null; }

  /** Language-specific completions. */
  default List<CompletionItem> completions(String fileUri, Position position, CoreQuery core) { return List.of(); }

  /** Forget any cached state for file. */
  default void forget(String fileUri) {}

}

