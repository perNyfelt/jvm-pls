package se.alipsa.jvmpls.core;

import java.util.List;
import java.util.Optional;

import se.alipsa.jvmpls.core.model.*;

/** Transport-agnostic API that both the in-proc server and the LSP adapter call. */
public interface CoreFacade {

  /** Open (or replace) a file’s content. Triggers (re)indexing. */
  List<Diagnostic> openFile(String uri, String text);

  /** Update a file’s content. Triggers (re)indexing. */
  List<Diagnostic> changeFile(String uri, String text);

  /** Close a file and discard caches and diagnostics. */
  void closeFile(String uri);

  /** (Re)analyze the current content of a file. */
  List<Diagnostic> analyze(String uri);

  /** Language-specific completions at a position. */
  List<CompletionItem> completions(String uri, Position position);

  /** Go to definition for the token at a position. */
  Optional<Location> definition(String uri, Position position);

  /** Hover payload for the symbol or expression at a position. */
  Optional<HoverInfo> hover(String uri, Position position);

  /** Find references for the symbol at a position. */
  List<Location> references(String uri, Position position, boolean includeDeclaration);

  /** Source declarations for one file. */
  List<SymbolInfo> documentSymbols(String uri);

  /** Workspace/global symbol search. */
  List<SymbolInfo> workspaceSymbols(String query);

  /** Signature help for the active call site. */
  Optional<SignatureHelpInfo> signatureHelp(String uri, Position position);

  /** Quick fixes and code actions for the current file/range. */
  List<CodeActionInfo> codeActions(String uri, Range range, List<Diagnostic> diagnostics);
}
