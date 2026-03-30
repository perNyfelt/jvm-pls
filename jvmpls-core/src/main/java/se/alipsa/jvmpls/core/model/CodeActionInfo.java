package se.alipsa.jvmpls.core.model;

import java.util.List;
import java.util.Objects;

/** Transport-neutral code action description with workspace edits. */
public final class CodeActionInfo {
  private final String title;
  private final String kind;
  private final List<TextEdit> edits;
  private final boolean preferred;

  public CodeActionInfo(String title, String kind, List<TextEdit> edits, boolean preferred) {
    this.title = Objects.requireNonNullElse(title, "");
    this.kind = kind == null ? "" : kind;
    this.edits = edits == null ? List.of() : List.copyOf(edits);
    this.preferred = preferred;
  }

  public String getTitle() {
    return title;
  }

  public String getKind() {
    return kind;
  }

  public List<TextEdit> getEdits() {
    return List.copyOf(edits);
  }

  public boolean isPreferred() {
    return preferred;
  }
}
