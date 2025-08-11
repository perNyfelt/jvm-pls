package se.alipsa.jvmpls.core;

import se.alipsa.jvmpls.core.model.CompletionItem;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.SymbolInfo;
import java.util.*;

public interface JvmLangPlugin {

  /**
   * Parse source file content and produce AST or internal representation.
   * @param fileUri URI or path of source file
   * @param content file contents
   * @return language-specific AST or parse tree object
   */
  Object parseSource(String fileUri, String content);

  /**
   * Analyze the parsed AST and report declared symbols (classes, methods, fields, etc).
   * @param ast language-specific AST object
   * @param symbolReporter callback to report symbols back to core server
   */
  void analyzeAndReportSymbols(Object ast, SymbolReporter symbolReporter);

  /**
   * Resolve symbol/type by simple name or qualified name in the context of a file.
   * @param fileUri current file context
   * @param symbolName symbol simple or qualified name to resolve
   * @return SymbolInfo or null if unresolved
   */
  SymbolInfo resolveSymbol(String fileUri, String symbolName);

  /**
   * Provide language-specific completions at given position.
   * @param fileUri source file
   * @param position offset or line/column
   * @return list of completion items
   */
  List<CompletionItem> getCompletions(String fileUri, Position position);

  /**
   * Provide diagnostics for a source file.
   * @param fileUri source file
   * @return list of diagnostics (errors, warnings)
   */
  List<Diagnostic> getDiagnostics(String fileUri);

  // Other language-specific features can be added here,
  // e.g. rename, refactor, hover, signature help, etc.

}

