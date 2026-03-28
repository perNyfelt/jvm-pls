package se.alipsa.jvmpls.core.types;

import java.util.Objects;

public record PrimitiveType(String name) implements JvmType {

  public PrimitiveType {
    name = Objects.requireNonNull(name, "name");
  }

  @Override
  public String displayName() {
    return name;
  }
}
