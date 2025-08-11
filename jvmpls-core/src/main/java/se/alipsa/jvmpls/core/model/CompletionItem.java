package se.alipsa.jvmpls.core.model;

public final class CompletionItem {
  private final String label;         // what the user sees in the list
  private final String detail;        // e.g., fully-qualified name
  private final String insertText;    // what to insert; defaults to label
  private final Location location;    // optional: where the symbol is declared
  private final java.util.List<TextEdit> additionalTextEdits;

  public CompletionItem(String label, String detail, String insertText, Location loc) {
    this(label, detail, insertText, loc, java.util.List.of());
  }

  public CompletionItem(String label, String detail, String insertText, Location loc,
                        java.util.List<TextEdit> additionalTextEdits) {
    this.label = label;
    this.detail = detail;
    this.insertText = insertText;
    this.location = loc;
    this.additionalTextEdits = additionalTextEdits;
  }

  public java.util.List<TextEdit> getAdditionalTextEdits() {
    return additionalTextEdits;
  }

  public String getLabel() {
    return label;
  }

  public String getDetail() {
    return detail;
  }

  public String getInsertText() {
    return insertText;
  }

  public Location getLocation() {
    return location;
  }
}
