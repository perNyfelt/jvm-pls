package se.alipsa.jvmpls.core.types;

public record WildcardType(Variance variance, JvmType bound) implements JvmType {

  public enum Variance {
    UNBOUNDED,
    EXTENDS,
    SUPER
  }

  public WildcardType {
    variance = variance == null ? Variance.UNBOUNDED : variance;
    if (variance != Variance.UNBOUNDED && bound == null) {
      throw new IllegalArgumentException("bound required for " + variance);
    }
  }

  @Override
  public String displayName() {
    return switch (variance) {
      case UNBOUNDED -> "?";
      case EXTENDS -> "? extends " + bound.displayName();
      case SUPER -> "? super " + bound.displayName();
    };
  }
}
