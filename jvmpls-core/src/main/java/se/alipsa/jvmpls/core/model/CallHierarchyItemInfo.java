package se.alipsa.jvmpls.core.model;

import java.util.Objects;

/** Transport-neutral call hierarchy item keyed by a stable symbol FQN. */
public final class CallHierarchyItemInfo {
  private final String symbolFqn;
  private final String name;
  private final String detail;
  private final SymbolInfo.Kind kind;
  private final Location location;
  private final Range selectionRange;

  public CallHierarchyItemInfo(
      String symbolFqn,
      String name,
      String detail,
      SymbolInfo.Kind kind,
      Location location,
      Range selectionRange) {
    this.symbolFqn = Objects.requireNonNullElse(symbolFqn, "");
    this.name = Objects.requireNonNullElse(name, "");
    this.detail = detail == null ? "" : detail;
    this.kind = Objects.requireNonNull(kind, "kind");
    this.location = Objects.requireNonNull(location, "location");
    this.selectionRange = selectionRange == null ? location.getRange() : selectionRange;
  }

  public String getSymbolFqn() {
    return symbolFqn;
  }

  public String getName() {
    return name;
  }

  public String getDetail() {
    return detail;
  }

  public SymbolInfo.Kind getKind() {
    return kind;
  }

  public Location getLocation() {
    return location;
  }

  public Range getSelectionRange() {
    return selectionRange;
  }
}
