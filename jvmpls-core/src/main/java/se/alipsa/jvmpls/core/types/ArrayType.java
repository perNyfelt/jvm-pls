package se.alipsa.jvmpls.core.types;

import java.util.Objects;

public record ArrayType(JvmType componentType) implements JvmType {

  public ArrayType {
    componentType = Objects.requireNonNull(componentType, "componentType");
  }

  @Override
  public String displayName() {
    return componentType.displayName() + "[]";
  }
}
