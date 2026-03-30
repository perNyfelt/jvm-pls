package se.alipsa.jvmpls.core.model;

import java.util.List;
import java.util.Objects;

/** Transport-neutral outgoing call hierarchy edge. */
public final class OutgoingCallInfo {
  private final CallHierarchyItemInfo to;
  private final List<Range> fromRanges;

  public OutgoingCallInfo(CallHierarchyItemInfo to, List<Range> fromRanges) {
    this.to = Objects.requireNonNull(to, "to");
    this.fromRanges = fromRanges == null ? List.of() : List.copyOf(fromRanges);
  }

  public CallHierarchyItemInfo getTo() {
    return to;
  }

  public List<Range> getFromRanges() {
    return fromRanges;
  }
}
