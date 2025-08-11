package se.alipsa.jvmpls.core;

import se.alipsa.jvmpls.core.model.Location;

public interface SymbolReporter {
  void reportPackage(String packageFqn, Location loc);
  void reportClass(String classFqn, Location loc, boolean isInterface, boolean isEnum, boolean isAnnotation);
  void reportMethod(String ownerClassFqn, String methodName, String signature, Location loc);
  void reportField(String ownerClassFqn, String fieldName, String typeFqn, Location loc);
  void reportAnnotation(String annotationFqn, Location loc);
}
