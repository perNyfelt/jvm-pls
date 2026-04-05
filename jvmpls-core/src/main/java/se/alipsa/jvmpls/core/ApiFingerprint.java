package se.alipsa.jvmpls.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import se.alipsa.jvmpls.core.model.SymbolInfo;

/**
 * Structural digest of a file's exported API surface. Includes public/protected types, methods,
 * fields, constructors, and supertype relationships. Excludes method bodies, private members,
 * comments, and formatting so that body-only edits produce identical fingerprints.
 */
public final class ApiFingerprint {

  private static final ApiFingerprint EMPTY = new ApiFingerprint("");

  private final String hash;

  private ApiFingerprint(String hash) {
    this.hash = hash;
  }

  /**
   * Compute the API fingerprint for a file from its current declarations in the index.
   *
   * @param fileUri the file URI
   * @param index the symbol index to query
   * @return the fingerprint, or an empty fingerprint if the file has no declarations
   */
  public static ApiFingerprint compute(String fileUri, SymbolIndex index) {
    List<SymbolInfo> decls = index.declarationsInFile(fileUri);
    if (decls.isEmpty()) {
      return EMPTY;
    }

    List<String> records = new ArrayList<>();
    for (SymbolInfo sym : decls) {
      if (isPrivate(sym)) {
        continue;
      }
      String record = toRecord(sym);
      if (record != null) {
        records.add(record);
      }

      // Include supertype relationships for type declarations
      if (isType(sym)) {
        List<String> supertypes = index.directSupertypesOfLocal(sym.getFqName());
        if (!supertypes.isEmpty()) {
          List<String> sorted = new ArrayList<>(supertypes);
          Collections.sort(sorted);
          records.add("SUPERTYPES:" + sym.getFqName() + ":" + String.join(",", sorted));
        }
      }
    }

    if (records.isEmpty()) {
      return EMPTY;
    }

    Collections.sort(records);
    StringBuilder sb = new StringBuilder();
    for (String r : records) {
      sb.append(r).append('\n');
    }

    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      return new ApiFingerprint(HexFormat.of().formatHex(digest));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed by the JVM spec
      throw new AssertionError("SHA-256 not available", e);
    }
  }

  private static boolean isPrivate(SymbolInfo sym) {
    return sym.getModifiers().contains("private");
  }

  private static boolean isType(SymbolInfo sym) {
    return sym.getKind() == SymbolInfo.Kind.CLASS
        || sym.getKind() == SymbolInfo.Kind.INTERFACE
        || sym.getKind() == SymbolInfo.Kind.ENUM
        || sym.getKind() == SymbolInfo.Kind.ANNOTATION;
  }

  private static String toRecord(SymbolInfo sym) {
    String modifiers = sortedModifiers(sym.getModifiers());
    return switch (sym.getKind()) {
      case CLASS, INTERFACE, ENUM, ANNOTATION ->
          sym.getKind() + ":" + sym.getFqName() + ":" + modifiers;
      case METHOD -> "METHOD:" + sym.getFqName() + ":" + sym.getSignature() + ":" + modifiers;
      case CONSTRUCTOR ->
          "CONSTRUCTOR:" + sym.getFqName() + ":" + sym.getSignature() + ":" + modifiers;
      case FIELD -> "FIELD:" + sym.getFqName() + ":" + sym.getSignature() + ":" + modifiers;
      case PACKAGE -> null;
    };
  }

  private static String sortedModifiers(Set<String> modifiers) {
    if (modifiers.isEmpty()) {
      return "";
    }
    return String.join(",", new TreeSet<>(modifiers));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ApiFingerprint other)) return false;
    return hash.equals(other.hash);
  }

  @Override
  public int hashCode() {
    return hash.hashCode();
  }

  @Override
  public String toString() {
    return "ApiFingerprint[" + (hash.isEmpty() ? "empty" : hash.substring(0, 8)) + "]";
  }
}
