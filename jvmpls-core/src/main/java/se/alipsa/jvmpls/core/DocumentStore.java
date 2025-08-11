package se.alipsa.jvmpls.core;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Simple in-memory text store for open documents. */
public final class DocumentStore {
  private final Map<String, String> byUri = new ConcurrentHashMap<>();

  public void put(String uri, String text) {
    byUri.put(Objects.requireNonNull(uri), Objects.requireNonNull(text));
  }

  public String get(String uri) {
    return byUri.get(uri);
  }

  public void remove(String uri) {
    byUri.remove(uri);
  }
}
