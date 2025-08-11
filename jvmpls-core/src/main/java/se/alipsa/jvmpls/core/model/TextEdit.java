package se.alipsa.jvmpls.core.model;

public final class TextEdit {
  private final Range range;
  private final String newText;
  public TextEdit(Range range, String newText) { this.range = range; this.newText = newText; }
  public Range getRange() { return range; }
  public String getNewText() { return newText; }
}