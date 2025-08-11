package se.alipsa.jvmpls.core.model;

import java.util.List;
import java.util.Set;

public final class SymbolInfo {
  public enum Kind { PACKAGE, CLASS, INTERFACE, ENUM, METHOD, FIELD, ANNOTATION }

  private final String languageId;     // plugin id(), e.g. "java"
  private final Kind kind;
  private final String fqName;         // a.b.C#foo(int) or a.b.C.x
  private final String containerFqName;
  private final Location location;
  private final String signature;
  private final Set<String> modifiers;
  private final List<String> typeParameters;

  public SymbolInfo(String languageId, Kind kind, String fqName,
                    String containerFqName, Location location,
                    String signature, Set<String> modifiers,
                    List<String> typeParameters) {
    this.languageId = languageId;
    this.kind = kind;
    this.fqName = fqName;
    this.containerFqName = containerFqName;
    this.location = location;
    this.signature = signature;
    this.modifiers = modifiers;
    this.typeParameters = typeParameters;
  }

  public String getLanguageId() { return languageId; }
  public Kind getKind() { return kind; }
  public String getFqName() { return fqName; }
  public String getContainerFqName() { return containerFqName; }
  public Location getLocation() { return location; }
  public String getSignature() { return signature; }
  public Set<String> getModifiers() { return modifiers; }
  public List<String> getTypeParameters() { return typeParameters; }
}

