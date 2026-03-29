package se.alipsa.jvmpls.core.types;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record TypeVariable(String name, List<JvmType> upperBounds) implements JvmType {

  public TypeVariable {
    name = Objects.requireNonNull(name, "name");
    upperBounds = upperBounds == null ? List.of() : List.copyOf(upperBounds);
  }

  @Override
  public String displayName() {
    if (upperBounds.isEmpty()) {
      return name;
    }
    return name
        + " extends "
        + upperBounds.stream().map(JvmType::displayName).collect(Collectors.joining(" & "));
  }
}
