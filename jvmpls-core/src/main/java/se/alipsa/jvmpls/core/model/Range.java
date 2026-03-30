package se.alipsa.jvmpls.core.model;

import java.util.Objects;

public class Range {
  public final Position start;
  public final Position end;

  public Range(Position start, Position end) {
    this.start = start;
    this.end = end;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Range that)) {
      return false;
    }
    return Objects.equals(start, that.start) && Objects.equals(end, that.end);
  }

  @Override
  public int hashCode() {
    return Objects.hash(start, end);
  }
}
