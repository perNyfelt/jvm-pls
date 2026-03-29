package se.alipsa.jvmpls.core.types;

public final class VoidType implements JvmType {

  public static final VoidType INSTANCE = new VoidType();

  private VoidType() {}

  @Override
  public String displayName() {
    return "void";
  }
}
