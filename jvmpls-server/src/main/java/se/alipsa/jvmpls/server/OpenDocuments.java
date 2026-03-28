package se.alipsa.jvmpls.server;

import se.alipsa.jvmpls.core.CoreFacade;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

final class OpenDocuments {

  private final ConcurrentHashMap<String, DocumentState> documentsByUri = new ConcurrentHashMap<>();

  void open(String uri, String languageId, int version, String text) {
    documentsByUri.put(uri, new DocumentState(uri, languageId, version, text));
  }

  void change(String uri, int version, String text) {
    documentsByUri.computeIfPresent(uri,
        (ignored, existing) -> new DocumentState(uri, existing.languageId(), version, text));
  }

  void close(String uri) {
    documentsByUri.remove(uri);
  }

  List<DocumentState> snapshot() {
    return new ArrayList<>(documentsByUri.values());
  }

  void replayInto(CoreFacade core) {
    for (DocumentState document : snapshot()) {
      core.openFile(document.uri(), document.text());
    }
  }

  record DocumentState(String uri, String languageId, int version, String text) {
  }
}
