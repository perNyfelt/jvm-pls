package se.alipsa.jvmpls.core.model;

import se.alipsa.jvmpls.core.types.JvmType;
import se.alipsa.jvmpls.core.types.MethodSignature;

import java.util.List;
import java.util.Set;

public final class SymbolInfo {
  public enum Kind { PACKAGE, CLASS, INTERFACE, ENUM, METHOD, CONSTRUCTOR, FIELD, ANNOTATION }

  private final String languageId;     // plugin id(), e.g. "java"
  private final Kind kind;
  private final String fqName;         // a.b.C#foo(int) or a.b.C.x
  private final String containerFqName;
  private final Location location;
  private final String signature;
  private final Set<String> modifiers;
  private final List<String> typeParameters;
  private final JvmType resolvedType;
  private final MethodSignature methodSignature;
  private final SyntheticOrigin syntheticOrigin;
  private final InferenceConfidence inferenceConfidence;

  public SymbolInfo(String languageId, Kind kind, String fqName,
                    String containerFqName, Location location,
                    String signature, Set<String> modifiers,
                    List<String> typeParameters) {
    this(languageId, kind, fqName, containerFqName, location, signature, modifiers,
        typeParameters, null, null, SyntheticOrigin.NONE, InferenceConfidence.DETERMINISTIC);
  }

  public SymbolInfo(String languageId, Kind kind, String fqName,
                    String containerFqName, Location location,
                    String signature, Set<String> modifiers,
                    List<String> typeParameters,
                    JvmType resolvedType,
                    MethodSignature methodSignature) {
    this(languageId, kind, fqName, containerFqName, location, signature, modifiers,
        typeParameters, resolvedType, methodSignature,
        SyntheticOrigin.NONE, InferenceConfidence.DETERMINISTIC);
  }

  public SymbolInfo(String languageId, Kind kind, String fqName,
                    String containerFqName, Location location,
                    String signature, Set<String> modifiers,
                    List<String> typeParameters,
                    JvmType resolvedType,
                    MethodSignature methodSignature,
                    SyntheticOrigin syntheticOrigin,
                    InferenceConfidence inferenceConfidence) {
    this.languageId = languageId;
    this.kind = kind;
    this.fqName = fqName;
    this.containerFqName = containerFqName;
    this.location = location;
    this.signature = signature;
    this.modifiers = modifiers;
    this.typeParameters = typeParameters;
    this.resolvedType = resolvedType;
    this.methodSignature = methodSignature;
    this.syntheticOrigin = syntheticOrigin == null ? SyntheticOrigin.NONE : syntheticOrigin;
    this.inferenceConfidence = inferenceConfidence == null
        ? InferenceConfidence.DETERMINISTIC
        : inferenceConfidence;
  }

  public String getLanguageId() { return languageId; }
  public Kind getKind() { return kind; }
  public String getFqName() { return fqName; }
  public String getContainerFqName() { return containerFqName; }
  public Location getLocation() { return location; }
  public String getSignature() { return signature; }
  public Set<String> getModifiers() { return modifiers; }
  public List<String> getTypeParameters() { return typeParameters; }
  public JvmType getResolvedType() { return resolvedType; }
  public MethodSignature getMethodSignature() { return methodSignature; }
  public SyntheticOrigin getSyntheticOrigin() { return syntheticOrigin; }
  public InferenceConfidence getInferenceConfidence() { return inferenceConfidence; }
}
