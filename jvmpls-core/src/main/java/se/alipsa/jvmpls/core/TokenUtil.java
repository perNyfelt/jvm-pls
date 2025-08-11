package se.alipsa.jvmpls.core;

/** Tiny helpers for token/position math that don't depend on any language-specific lexer. */
public final class TokenUtil {
  private TokenUtil() {}

  public static int positionToOffset(String text, int line, int column) {
    int curLine = 0, idx = 0, n = text.length();
    while (curLine < line && idx < n) {
      int nl = text.indexOf('\n', idx);
      if (nl < 0) return n;
      idx = nl + 1;
      curLine++;
    }
    return Math.min(idx + column, n);
  }

  public static String tokenAt(String text, int offset) {
    if (text == null || text.isEmpty()) return "";
    int n = text.length();
    int i = Math.max(0, Math.min(offset, n - 1));
    if (!isWord(text.charAt(i)) && i > 0 && isWord(text.charAt(i - 1))) i--;

    int s = i, e = i + 1;
    while (s > 0 && isWord(text.charAt(s - 1))) s--;
    while (e < n && isWord(text.charAt(e))) e++;
    return text.substring(s, e);
  }

  public static CharSequence preview(String text) {
    int n = Math.min(text == null ? 0 : text.length(), 1024);
    return text == null ? "" : text.subSequence(0, n);
  }

  private static boolean isWord(char c) {
    // remove '.' so tokens stop at member access / package separators
    return Character.isAlphabetic(c) || Character.isDigit(c) || c == '_' || c == '$';
  }
}
