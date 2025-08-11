package se.alipsa.jvmpls.core.model;

import java.util.Objects;

/**
 * A source location: a document URI plus a range within that document.
 * <p>
 * The URI is typically a {@code file://} URL, but may also be a {@code jar://},
 * {@code mem://}, or any scheme your server supports.
 */
public final class Location {
  private final String uri;
  private final Range range;

  public Location(String uri, Range range) {
    this.uri = Objects.requireNonNull(uri, "uri");
    this.range = Objects.requireNonNull(range, "range");
  }

  public String getUri() {
    return uri;
  }

  public Range getRange() {
    return range;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Location that)) return false;
    return uri.equals(that.uri) && range.equals(that.range);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uri, range);
  }

  @Override
  public String toString() {
    return uri + "@" + range;
  }
}
