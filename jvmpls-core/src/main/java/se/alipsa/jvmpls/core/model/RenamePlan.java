package se.alipsa.jvmpls.core.model;

import java.util.List;
import java.util.Objects;

/** Transport-neutral rename plan with edits and any validation conflicts. */
public final class RenamePlan {
  private final SymbolInfo target;
  private final PrepareRenameInfo preparation;
  private final WorkspaceEditInfo workspaceEdit;
  private final List<RenameConflict> conflicts;

  public RenamePlan(
      SymbolInfo target,
      PrepareRenameInfo preparation,
      WorkspaceEditInfo workspaceEdit,
      List<RenameConflict> conflicts) {
    this.target = Objects.requireNonNull(target, "target");
    this.preparation = Objects.requireNonNull(preparation, "preparation");
    this.workspaceEdit = Objects.requireNonNull(workspaceEdit, "workspaceEdit");
    this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
  }

  public SymbolInfo getTarget() {
    return target;
  }

  public PrepareRenameInfo getPreparation() {
    return preparation;
  }

  public WorkspaceEditInfo getWorkspaceEdit() {
    return workspaceEdit;
  }

  public List<RenameConflict> getConflicts() {
    return conflicts;
  }

  public boolean isValid() {
    return conflicts.isEmpty();
  }
}
