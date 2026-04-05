package se.alipsa.jvmpls.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;

import se.alipsa.jvmpls.core.CoreFacade;
import se.alipsa.jvmpls.core.DocumentStore;

final class OpenDocuments {

  private static final Logger LOG = Logger.getLogger(OpenDocuments.class.getName());

  private final ConcurrentHashMap<String, DocumentState> documentsByUri = new ConcurrentHashMap<>();

  void open(String uri, String languageId, int version, String text) {
    documentsByUri.put(uri, new DocumentState(uri, languageId, version, text));
  }

  void change(String uri, int version, String text) {
    documentsByUri.computeIfPresent(
        uri, (ignored, existing) -> new DocumentState(uri, existing.languageId(), version, text));
  }

  /**
   * Applies a sequence of incremental (ranged or full) content changes to the document and updates
   * its version. Each change is applied in order; ranged changes are relative to the state after
   * the previous change in the same notification. Returns the final full text.
   *
   * @throws IllegalArgumentException if the document is not currently open
   */
  String applyIncrementalChanges(
      String uri, int version, List<TextDocumentContentChangeEvent> changes) {
    DocumentState state = documentsByUri.get(uri);
    if (state == null) {
      throw new IllegalArgumentException("No open document for URI: " + uri);
    }
    String text = state.text();
    for (TextDocumentContentChangeEvent change : changes) {
      Range range = change.getRange();
      if (range == null) {
        // Full content replacement (no range means the whole document)
        text = change.getText();
      } else {
        int startOffset =
            DocumentStore.toOffset(
                text, range.getStart().getLine(), range.getStart().getCharacter());
        int endOffset =
            DocumentStore.toOffset(text, range.getEnd().getLine(), range.getEnd().getCharacter());
        text = text.substring(0, startOffset) + change.getText() + text.substring(endOffset);
      }
    }
    String finalText = text;
    documentsByUri.put(uri, new DocumentState(uri, state.languageId(), version, finalText));
    return finalText;
  }

  void close(String uri) {
    documentsByUri.remove(uri);
  }

  String text(String uri) {
    DocumentState state = documentsByUri.get(uri);
    return state == null ? null : state.text();
  }

  List<DocumentState> snapshot() {
    return new ArrayList<>(documentsByUri.values());
  }

  void replayInto(CoreFacade core) {
    for (DocumentState document : snapshot()) {
      try {
        core.openFile(document.uri(), document.text());
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "Failed to replay open document " + document.uri(), e);
      }
    }
  }

  record DocumentState(String uri, String languageId, int version, String text) {}
}
