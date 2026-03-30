package se.alipsa.jvmpls.core.model;

import java.util.Objects;

/** Transport-neutral prepare-rename result with the editable range and current symbol name. */
public final class PrepareRenameInfo {
  private final Range range;
  private final String placeholder;

  public PrepareRenameInfo(Range range, String placeholder) {
    this.range = Objects.requireNonNull(range, "range");
    this.placeholder = placeholder == null ? "" : placeholder;
  }

  public Range getRange() {
    return range;
  }

  public String getPlaceholder() {
    return placeholder;
  }
}
