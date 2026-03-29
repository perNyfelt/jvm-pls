package se.alipsa.jvmpls.core.model;

public enum SyntheticOrigin {
  NONE,
  TRANSFORM,
  DELEGATE,
  BUILDER,
  LOG_FIELD,
  METACLASS,
  CATEGORY,
  MIXIN,
  METHOD_MISSING,
  PROPERTY_MISSING
}
