package se.alipsa.jvmpls.core.model;

public final class CompletionItem {
  private final String label;         // what the user sees in the list
  private final String detail;        // e.g., fully-qualified name
  private final String insertText;    // what to insert; defaults to label
  private final Location location;    // optional: where the symbol is declared

  public CompletionItem(String label) {
    this(label, null, null, null);
  }

  public CompletionItem(String label, String detail, String insertText, Location location) {
    this.label = label;
    this.detail = detail;
    this.insertText = insertText;
    this.location = location;
  }

  public String getLabel() { return label; }
  public String getDetail() { return detail; }
  public String getInsertText() { return insertText; }
  public Location getLocation() { return location; }
}
