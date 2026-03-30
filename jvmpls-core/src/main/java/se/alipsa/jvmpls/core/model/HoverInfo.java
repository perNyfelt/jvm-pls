package se.alipsa.jvmpls.core.model;

import java.util.Objects;

public final class HoverInfo {
  private final String title;
  private final String detail;
  private final String documentation;
  private final String provenance;
  private final Location location;

  public HoverInfo(
      String title, String detail, String documentation, String provenance, Location location) {
    this.title = Objects.requireNonNullElse(title, "");
    this.detail = detail == null ? "" : detail;
    this.documentation = documentation == null ? "" : documentation;
    this.provenance = provenance == null ? "" : provenance;
    this.location = location;
  }

  public String getTitle() {
    return title;
  }

  public String getDetail() {
    return detail;
  }

  public String getDocumentation() {
    return documentation;
  }

  public String getProvenance() {
    return provenance;
  }

  public Location getLocation() {
    return location;
  }
}
