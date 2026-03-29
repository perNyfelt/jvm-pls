package se.alipsa.jvmpls.core.types;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record MethodSignature(
    List<JvmType> parameterTypes,
    JvmType returnType,
    List<String> parameterNames,
    List<String> typeParameters,
    List<JvmType> throwsTypes,
    Set<String> modifiers) {

  public MethodSignature {
    parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
    returnType = Objects.requireNonNullElse(returnType, VoidType.INSTANCE);
    parameterNames = parameterNames == null ? List.of() : List.copyOf(parameterNames);
    typeParameters = typeParameters == null ? List.of() : List.copyOf(typeParameters);
    throwsTypes = throwsTypes == null ? List.of() : List.copyOf(throwsTypes);
    modifiers = modifiers == null ? Set.of() : Set.copyOf(modifiers);
  }
}
