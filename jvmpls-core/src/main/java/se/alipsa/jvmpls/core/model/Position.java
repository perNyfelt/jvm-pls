package se.alipsa.jvmpls.core.model;

public class Position {
  public final int line;   // zero-based line number
  public final int column; // zero-based column offset

  public Position(int line, int column) {
    this.line = line;
    this.column = column;
  }
}
