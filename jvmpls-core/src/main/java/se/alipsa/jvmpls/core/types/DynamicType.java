package se.alipsa.jvmpls.core.types;

public final class DynamicType implements JvmType {

  public static final DynamicType INSTANCE = new DynamicType();

  private DynamicType() {}

  @Override
  public String displayName() {
    return "dynamic";
  }
}
