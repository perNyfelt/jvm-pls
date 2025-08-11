package se.alipsa.jvmpls.core;

import se.alipsa.jvmpls.core.model.Range;

public interface SymbolReporter {
  void reportClass(String fqcn, Range location);
  void reportMethod(String fqcn, String methodName, Range location);
  void reportField(String fqcn, String fieldName, Range location);
  void reportAnnotation(String fqcn, Range location);
}
