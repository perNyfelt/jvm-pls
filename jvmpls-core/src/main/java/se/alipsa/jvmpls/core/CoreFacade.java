package se.alipsa.jvmpls.core;

import se.alipsa.jvmpls.core.model.*;

import java.util.List;
import java.util.Optional;

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
}