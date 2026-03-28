package se.alipsa.jvmpls.core.types;

import java.util.List;
import java.util.Objects;

public final class TypeRelations {

  public boolean isAssignableTo(JvmType source, JvmType target) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    if (source instanceof DynamicType || target instanceof DynamicType) {
      return true;
    }
    if (source.displayName().equals(target.displayName())) {
      return true;
    }
    if (target instanceof ClassType targetClass && "java.lang.Object".equals(targetClass.fqName())) {
      return true;
    }
    if (source instanceof ArrayType && target instanceof ClassType targetClass
        && "java.lang.Object".equals(targetClass.fqName())) {
      return true;
    }
    return false;
  }

  public boolean isSubtypeOf(JvmType source, JvmType target) {
    return isAssignableTo(source, target);
  }

  public JvmType commonSupertype(JvmType left, JvmType right) {
    if (left.displayName().equals(right.displayName())) {
      return left;
    }
    if (left instanceof DynamicType || right instanceof DynamicType) {
      return DynamicType.INSTANCE;
    }
    return new ClassType("java.lang.Object", List.of());
  }
}
