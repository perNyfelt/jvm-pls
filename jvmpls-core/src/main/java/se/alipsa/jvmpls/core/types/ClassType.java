package se.alipsa.jvmpls.core.types;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record ClassType(String fqName, List<JvmType> typeArguments) implements JvmType {

  public ClassType {
    fqName = Objects.requireNonNull(fqName, "fqName");
    typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
  }

  @Override
  public String displayName() {
    if (typeArguments.isEmpty()) {
      return fqName;
    }
    return fqName + "<" + typeArguments.stream()
        .map(JvmType::displayName)
        .collect(Collectors.joining(", ")) + ">";
  }
}
