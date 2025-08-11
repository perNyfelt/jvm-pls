package se.alipsa.jvmpls.core.model;
import java.util.Objects;

public class Diagnostic {
  public enum Severity {
    ERROR,
    WARNING,
    INFORMATION,
    HINT
  }

  private final Range range;
  private final String message;
  private final Severity severity;
  private final String source;  // e.g. "JvmLangPlugin"
  private final String code;    // optional diagnostic code/id

  public Diagnostic(Range range, String message, Severity severity, String source, String code) {
    this.range = Objects.requireNonNull(range, "range cannot be null");
    this.message = Objects.requireNonNull(message, "message cannot be null");
    this.severity = severity != null ? severity : Severity.ERROR;
    this.source = source;
    this.code = code;
  }

  public Range getRange() {
    return range;
  }

  public String getMessage() {
    return message;
  }

  public Severity getSeverity() {
    return severity;
  }

  public String getSource() {
    return source;
  }

  public String getCode() {
    return code;
  }

  @Override
  public String toString() {
    return "Diagnostic{" +
        "range=" + range +
        ", message='" + message + '\'' +
        ", severity=" + severity +
        ", source='" + source + '\'' +
        ", code='" + code + '\'' +
        '}';
  }
}
