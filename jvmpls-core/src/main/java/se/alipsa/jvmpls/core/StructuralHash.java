package se.alipsa.jvmpls.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
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

  // Matches field declarations (not inside method bodies — heuristic: no leading brace depth).
  // The modifier group is optional to also catch Groovy-style unmodified fields like "String name".
  private static final Pattern FIELD_DECL =
      Pattern.compile(
          "(?m)^\\s*(?:(?:public|protected|private|static|final|transient|volatile)\\s+)*"
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
    appendFieldDeclarations(structural, source);

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
      appendNormalized(sb, m.group());
    }
  }

  private static void appendFieldDeclarations(StringBuilder sb, String source) {
    ArrayDeque<BlockKind> blocks = new ArrayDeque<>();
    for (String line : source.split("\\R", -1)) {
      if (isFieldDeclaration(line, blocks)) {
        appendNormalized(sb, line);
      }
      updateBlocks(blocks, line);
    }
  }

  private static boolean isFieldDeclaration(String line, ArrayDeque<BlockKind> blocks) {
    if (!FIELD_DECL.matcher(line).matches()) {
      return false;
    }
    boolean insideType = false;
    for (BlockKind block : blocks) {
      if (block == BlockKind.TYPE) {
        insideType = true;
      } else {
        return false;
      }
    }
    return insideType;
  }

  private static void updateBlocks(ArrayDeque<BlockKind> blocks, String line) {
    BlockKind firstOpenKind = firstOpenKind(line);
    boolean classifiedFirstOpen = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '{') {
        blocks.addLast(classifiedFirstOpen ? BlockKind.OTHER : firstOpenKind);
        classifiedFirstOpen = true;
      } else if (ch == '}' && !blocks.isEmpty()) {
        blocks.removeLast();
      }
    }
  }

  private static BlockKind firstOpenKind(String line) {
    if (line.indexOf('{') < 0) {
      return BlockKind.OTHER;
    }
    if (TYPE_DECL.matcher(line).find()) {
      return BlockKind.TYPE;
    }
    if (METHOD_SIG.matcher(line).find()) {
      return BlockKind.EXECUTABLE;
    }
    return BlockKind.OTHER;
  }

  private static void appendNormalized(StringBuilder sb, String text) {
    // Normalize whitespace to make hash stable across formatting changes
    sb.append(text.replaceAll("\\s+", " ").trim()).append('\n');
  }

  private enum BlockKind {
    TYPE,
    EXECUTABLE,
    OTHER
  }
}
