package se.alipsa.jvmpls.core.model;

import java.util.List;

public final class CompletionItem {
  private final String label; // what the user sees in the list
  private final String detail; // e.g., fully-qualified name
  private final String insertText; // what to insert; defaults to label
  private final Location location; // optional: where the symbol is declared
  private final java.util.List<TextEdit> additionalTextEdits;
  private final String typeDetail;
  private final SyntheticOrigin syntheticOrigin;
  private final InferenceConfidence inferenceConfidence;

  public CompletionItem(String label, String detail, String insertText, Location loc) {
    this(
        label,
        detail,
        insertText,
        loc,
        java.util.List.of(),
        "",
        SyntheticOrigin.NONE,
        InferenceConfidence.DETERMINISTIC);
  }

  public CompletionItem(
      String label,
      String detail,
      String insertText,
      Location loc,
      java.util.List<TextEdit> additionalTextEdits) {
    this(
        label,
        detail,
        insertText,
        loc,
        additionalTextEdits,
        "",
        SyntheticOrigin.NONE,
        InferenceConfidence.DETERMINISTIC);
  }

  public CompletionItem(
      String label,
      String detail,
      String insertText,
      Location loc,
      java.util.List<TextEdit> additionalTextEdits,
      String typeDetail) {
    this(
        label,
        detail,
        insertText,
        loc,
        additionalTextEdits,
        typeDetail,
        SyntheticOrigin.NONE,
        InferenceConfidence.DETERMINISTIC);
  }

  public CompletionItem(
      String label,
      String detail,
      String insertText,
      Location loc,
      java.util.List<TextEdit> additionalTextEdits,
      String typeDetail,
      SyntheticOrigin syntheticOrigin,
      InferenceConfidence inferenceConfidence) {
    this.label = label;
    this.detail = detail;
    this.insertText = insertText;
    this.location = loc;
    this.additionalTextEdits =
        additionalTextEdits == null ? List.of() : List.copyOf(additionalTextEdits);
    this.typeDetail = typeDetail == null ? "" : typeDetail;
    this.syntheticOrigin = syntheticOrigin == null ? SyntheticOrigin.NONE : syntheticOrigin;
    this.inferenceConfidence =
        inferenceConfidence == null ? InferenceConfidence.DETERMINISTIC : inferenceConfidence;
  }

  public java.util.List<TextEdit> getAdditionalTextEdits() {
    return List.copyOf(additionalTextEdits);
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

  public String getTypeDetail() {
    return typeDetail;
  }

  public SyntheticOrigin getSyntheticOrigin() {
    return syntheticOrigin;
  }

  public InferenceConfidence getInferenceConfidence() {
    return inferenceConfidence;
  }
}
