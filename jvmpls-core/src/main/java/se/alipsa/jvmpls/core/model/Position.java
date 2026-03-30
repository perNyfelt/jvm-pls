package se.alipsa.jvmpls.core.model;

import java.util.Objects;

public class Position {
  public final int line; // zero-based line number
  public final int column; // zero-based column offset

  public Position(int line, int column) {
    this.line = line;
    this.column = column;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Position that)) {
      return false;
    }
    return line == that.line && column == that.column;
  }

  @Override
  public int hashCode() {
    return Objects.hash(line, column);
  }
}
