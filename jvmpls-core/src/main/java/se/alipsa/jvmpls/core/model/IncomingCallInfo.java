package se.alipsa.jvmpls.core.model;

import java.util.List;
import java.util.Objects;

/** Transport-neutral incoming call hierarchy edge. */
public final class IncomingCallInfo {
  private final CallHierarchyItemInfo from;
  private final List<Range> fromRanges;

  public IncomingCallInfo(CallHierarchyItemInfo from, List<Range> fromRanges) {
    this.from = Objects.requireNonNull(from, "from");
    this.fromRanges = fromRanges == null ? List.of() : List.copyOf(fromRanges);
  }

  public CallHierarchyItemInfo getFrom() {
    return from;
  }

  public List<Range> getFromRanges() {
    return fromRanges;
  }
}
