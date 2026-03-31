package se.alipsa.jvmpls.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes a structural hash of source code that includes package declarations, imports, class
 * headers, method/constructor signatures, and field declarations, but excludes method bodies. Two
 * source files with identical structural hashes differ only in method/constructor body content,
 * comments, or whitespace — meaning their exported symbols and dependencies are unchanged.
 *
 * <p>This is a lightweight heuristic based on text patterns rather than full AST parsing, so it can
 * run before (and potentially skip) an expensive parse.
 */
public final class StructuralHash {

  private StructuralHash() {}

  // Matches package declarations
  private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;");

  // Matches import declarations
  private static final Pattern IMPORT_DECL =
      Pattern.compile("(?m)^\\s*import(?:\\s+static)?\\s+[\\w.*]+\\s*;");

  // Matches class/interface/enum/annotation/record declarations (header line)
  private static final Pattern TYPE_DECL =
      Pattern.compile("(?m)^[\\s\\w]*\\b(?:class|interface|enum|record|@interface)\\s+\\w+[^{]*");

  // Matches method/constructor signatures: modifiers + return type + name + params, up to the
  // opening brace or semicolon. This is intentionally broad to catch the structural shape.
  private static final Pattern METHOD_SIG =
      Pattern.compile(
          "(?m)^\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native"
              + "|default|strictfp|transient|volatile)\\s+)*"
              + "(?:<[^>]+>\\s+)?" // optional type parameters
              + "(?:[\\w.<>\\[\\],?\\s]+\\s+)?" // return type (absent for constructors)
              + "\\w+\\s*\\([^)]*\\)" // name + params
              + "(?:\\s*throws\\s+[\\w.,\\s]+)?" // optional throws
              + "\\s*[{;]");

  // Matches field declarations (not inside method bodies — heuristic: no leading brace depth)
  private static final Pattern FIELD_DECL =
      Pattern.compile(
          "(?m)^\\s*(?:(?:public|protected|private|static|final|transient|volatile)\\s+)+"
              + "[\\w.<>\\[\\],?]+\\s+\\w+(?:\\s*=[^;]*)?;");

  // Matches annotation usage on declarations
  private static final Pattern ANNOTATION = Pattern.compile("(?m)^\\s*@\\w+(?:\\([^)]*\\))?\\s*$");

  /**
   * Compute a structural hash of the given source content. Returns a hex-encoded SHA-256 digest of
   * the structural elements.
   */
  public static String compute(String source) {
    StringBuilder structural = new StringBuilder();
    appendMatches(structural, PACKAGE_DECL, source);
    appendMatches(structural, IMPORT_DECL, source);
    appendMatches(structural, ANNOTATION, source);
    appendMatches(structural, TYPE_DECL, source);
    appendMatches(structural, METHOD_SIG, source);
    appendMatches(structural, FIELD_DECL, source);

    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(structural.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 not available", e);
    }
  }

  private static void appendMatches(StringBuilder sb, Pattern pattern, String source) {
    Matcher m = pattern.matcher(source);
    while (m.find()) {
      // Normalize whitespace to make hash stable across formatting changes
      sb.append(m.group().replaceAll("\\s+", " ").trim()).append('\n');
    }
  }
}
