package se.alipsa.jvmpls.core.types;

/**
 * Structured representation of a JVM type.
 */
public sealed interface JvmType permits PrimitiveType, ClassType, ArrayType,
    TypeVariable, WildcardType, VoidType, DynamicType {

  String displayName();
}
