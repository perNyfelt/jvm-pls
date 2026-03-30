package se.alipsa.jvmpls.core.model;

import java.util.List;
import java.util.Objects;

public final class CallableInfo {
  private final String label;
  private final List<String> parameterLabels;
  private final String documentation;
  private final Location location;

  public CallableInfo(
      String label, List<String> parameterLabels, String documentation, Location location) {
    this.label = Objects.requireNonNullElse(label, "");
    this.parameterLabels = parameterLabels == null ? List.of() : List.copyOf(parameterLabels);
    this.documentation = documentation == null ? "" : documentation;
    this.location = location;
  }

  public String getLabel() {
    return label;
  }

  public List<String> getParameterLabels() {
    return List.copyOf(parameterLabels);
  }

  public String getDocumentation() {
    return documentation;
  }

  public Location getLocation() {
    return location;
  }
}
