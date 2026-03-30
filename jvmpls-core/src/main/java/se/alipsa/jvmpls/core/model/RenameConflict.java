package se.alipsa.jvmpls.core.model;

/** Validation problem discovered while building a rename plan. */
public final class RenameConflict {
  private final String code;
  private final String message;
  private final Location location;

  public RenameConflict(String code, String message, Location location) {
    this.code = code == null ? "" : code;
    this.message = message == null ? "" : message;
    this.location = location;
  }

  public String getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }

  public Location getLocation() {
    return location;
  }
}
