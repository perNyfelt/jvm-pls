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

  /**
   * Applies a range-based edit to the document identified by {@code uri}. Positions use 0-based
   * line and character offsets (LSP convention). The range {@code [startLine:startChar,
   * endLine:endChar)} is replaced with {@code newText}. An empty range (start equals end) is a pure
   * insertion. The updated text is stored and returned.
   *
   * @throws IllegalArgumentException if no text is stored for the given URI
   */
  public String applyEdit(
      String uri, int startLine, int startChar, int endLine, int endChar, String newText) {
    String text = byUri.get(uri);
    if (text == null) {
      throw new IllegalArgumentException("No stored text for URI: " + uri);
    }
    int startOffset = toOffset(text, startLine, startChar);
    int endOffset = toOffset(text, endLine, endChar);
    String updated = text.substring(0, startOffset) + newText + text.substring(endOffset);
    byUri.put(uri, updated);
    return updated;
  }

  /**
   * Converts a 0-based line and character position to a character offset within {@code text}.
   * Handles {@code \r\n}, {@code \r}, and {@code \n} line endings.
   */
  public static int toOffset(String text, int line, int character) {
    int offset = 0;
    int currentLine = 0;
    int length = text.length();
    while (currentLine < line && offset < length) {
      char ch = text.charAt(offset);
      if (ch == '\r') {
        offset++;
        if (offset < length && text.charAt(offset) == '\n') {
          offset++;
        }
        currentLine++;
      } else if (ch == '\n') {
        offset++;
        currentLine++;
      } else {
        offset++;
      }
    }
    return Math.min(offset + character, length);
  }
}
