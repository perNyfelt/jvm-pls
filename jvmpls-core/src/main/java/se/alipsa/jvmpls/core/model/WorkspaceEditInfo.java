package se.alipsa.jvmpls.core.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Transport-neutral multi-file text edits. */
public final class WorkspaceEditInfo {
  private final Map<String, List<TextEdit>> changes;

  public WorkspaceEditInfo(Map<String, List<TextEdit>> changes) {
    LinkedHashMap<String, List<TextEdit>> copy = new LinkedHashMap<>();
    if (changes != null) {
      changes.forEach(
          (uri, edits) -> copy.put(uri, edits == null ? List.of() : List.copyOf(edits)));
    }
    this.changes = Map.copyOf(copy);
  }

  public Map<String, List<TextEdit>> getChanges() {
    return changes;
  }

  public boolean isEmpty() {
    return changes.isEmpty() || changes.values().stream().allMatch(List::isEmpty);
  }
}
