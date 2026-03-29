package se.alipsa.jvmpls.core;

import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.InferenceConfidence;
import se.alipsa.jvmpls.core.model.SyntheticOrigin;
import se.alipsa.jvmpls.core.types.JvmType;
import se.alipsa.jvmpls.core.types.JvmTypes;
import se.alipsa.jvmpls.core.types.MethodSignature;

import java.util.Set;

public interface SymbolReporter {
  void reportPackage(String packageFqn, Location loc);
  void reportClass(String classFqn, Location loc, boolean isInterface, boolean isEnum, boolean isAnnotation);
  void reportMethod(String ownerClassFqn, String methodName, String signature, Location loc);
  void reportField(String ownerClassFqn, String fieldName, String typeFqn, Location loc);
  void reportAnnotation(String annotationFqn, Location loc);

  default void reportMethod(String ownerClassFqn, String methodName, MethodSignature signature,
                            Location loc, Set<String> modifiers) {
    reportMethod(ownerClassFqn, methodName, JvmTypes.toLegacyMethodSignature(signature), loc);
  }

  default void reportClass(String classFqn, Location loc, boolean isInterface, boolean isEnum, boolean isAnnotation,
                           SyntheticOrigin origin,
                           InferenceConfidence confidence) {
    reportClass(classFqn, loc, isInterface, isEnum, isAnnotation);
  }

  default void reportMethod(String ownerClassFqn, String methodName, MethodSignature signature,
                            Location loc, Set<String> modifiers,
                            SyntheticOrigin origin,
                            InferenceConfidence confidence) {
    reportMethod(ownerClassFqn, methodName, signature, loc, modifiers);
  }

  default void reportField(String ownerClassFqn, String fieldName, JvmType type,
                           Location loc, Set<String> modifiers) {
    reportField(ownerClassFqn, fieldName, type.displayName(), loc);
  }

  default void reportField(String ownerClassFqn, String fieldName, JvmType type,
                           Location loc, Set<String> modifiers,
                           SyntheticOrigin origin,
                           InferenceConfidence confidence) {
    reportField(ownerClassFqn, fieldName, type, loc, modifiers);
  }

  default void reportConstructor(String ownerClassFqn, MethodSignature signature,
                                 Location loc, Set<String> modifiers) {
  }

  default void reportConstructor(String ownerClassFqn, MethodSignature signature,
                                 Location loc, Set<String> modifiers,
                                 SyntheticOrigin origin,
                                 InferenceConfidence confidence) {
    reportConstructor(ownerClassFqn, signature, loc, modifiers);
  }
}
