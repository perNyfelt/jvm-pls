package se.alipsa.jvmpls.groovy;

import java.util.List;
import java.util.Set;

import se.alipsa.jvmpls.core.SymbolReporter;
import se.alipsa.jvmpls.core.model.InferenceConfidence;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.SymbolInfo;
import se.alipsa.jvmpls.core.model.SyntheticOrigin;
import se.alipsa.jvmpls.core.types.JvmType;
import se.alipsa.jvmpls.core.types.MethodSignature;

public record SyntheticMemberSpec(
    SymbolInfo.Kind kind,
    String ownerFqn,
    String memberName,
    Location location,
    Set<String> modifiers,
    JvmType resolvedType,
    MethodSignature methodSignature,
    SyntheticOrigin origin,
    InferenceConfidence confidence,
    boolean interfaceType,
    boolean enumType,
    boolean annotationType) {

  public SyntheticMemberSpec {
    modifiers = modifiers == null ? Set.of() : Set.copyOf(modifiers);
    origin = origin == null ? SyntheticOrigin.NONE : origin;
    confidence = confidence == null ? InferenceConfidence.DETERMINISTIC : confidence;
  }

  public static SyntheticMemberSpec syntheticClass(
      String classFqn, Location location, SyntheticOrigin origin, InferenceConfidence confidence) {
    return new SyntheticMemberSpec(
        SymbolInfo.Kind.CLASS,
        classFqn,
        null,
        location,
        Set.of(),
        null,
        null,
        origin,
        confidence,
        false,
        false,
        false);
  }

  public static SyntheticMemberSpec syntheticField(
      String ownerFqn,
      String fieldName,
      JvmType type,
      Location location,
      Set<String> modifiers,
      SyntheticOrigin origin,
      InferenceConfidence confidence) {
    return new SyntheticMemberSpec(
        SymbolInfo.Kind.FIELD,
        ownerFqn,
        fieldName,
        location,
        modifiers,
        type,
        null,
        origin,
        confidence,
        false,
        false,
        false);
  }

  public static SyntheticMemberSpec syntheticMethod(
      String ownerFqn,
      String methodName,
      MethodSignature signature,
      Location location,
      Set<String> modifiers,
      SyntheticOrigin origin,
      InferenceConfidence confidence) {
    return new SyntheticMemberSpec(
        SymbolInfo.Kind.METHOD,
        ownerFqn,
        methodName,
        location,
        modifiers,
        null,
        signature,
        origin,
        confidence,
        false,
        false,
        false);
  }

  public static SyntheticMemberSpec syntheticConstructor(
      String ownerFqn,
      MethodSignature signature,
      Location location,
      Set<String> modifiers,
      SyntheticOrigin origin,
      InferenceConfidence confidence) {
    return new SyntheticMemberSpec(
        SymbolInfo.Kind.CONSTRUCTOR,
        ownerFqn,
        null,
        location,
        modifiers,
        null,
        signature,
        origin,
        confidence,
        false,
        false,
        false);
  }

  public void report(SymbolReporter reporter) {
    switch (kind) {
      case CLASS ->
          reporter.reportClass(
              ownerFqn, location, interfaceType, enumType, annotationType, origin, confidence);
      case FIELD ->
          reporter.reportField(
              ownerFqn, memberName, resolvedType, location, modifiers, origin, confidence);
      case METHOD ->
          reporter.reportMethod(
              ownerFqn, memberName, methodSignature, location, modifiers, origin, confidence);
      case CONSTRUCTOR ->
          reporter.reportConstructor(
              ownerFqn, methodSignature, location, modifiers, origin, confidence);
      default -> throw new IllegalStateException("Unsupported synthetic kind: " + kind);
    }
  }

  public List<String> typeParameters() {
    return methodSignature == null ? List.of() : methodSignature.typeParameters();
  }
}
