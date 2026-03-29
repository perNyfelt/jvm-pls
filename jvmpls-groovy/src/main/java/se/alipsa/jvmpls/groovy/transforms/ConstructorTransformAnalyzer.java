package se.alipsa.jvmpls.groovy.transforms;

import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.PropertyNode;
import se.alipsa.jvmpls.core.model.InferenceConfidence;
import se.alipsa.jvmpls.core.model.SyntheticOrigin;
import se.alipsa.jvmpls.core.types.JvmType;
import se.alipsa.jvmpls.core.types.MethodSignature;
import se.alipsa.jvmpls.core.types.VoidType;
import se.alipsa.jvmpls.groovy.SyntheticMemberSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class ConstructorTransformAnalyzer implements TransformAnalyzer {

  @Override
  public List<SyntheticMemberSpec> analyze(ClassNode classNode, AnnotationNode annotation, TransformContext context) {
    ArrayList<JvmType> parameterTypes = new ArrayList<>();
    ArrayList<String> parameterNames = new ArrayList<>();
    for (PropertyNode property : classNode.getProperties()) {
      parameterTypes.add(context.typeOf().apply(property.getType()));
      parameterNames.add(property.getName());
    }
    if (parameterTypes.isEmpty()) {
      return List.of();
    }
    for (ConstructorNode constructor : classNode.getDeclaredConstructors()) {
      if (sameSignature(parameterTypes, constructor, context)) {
        return List.of();
      }
    }
    return List.of(SyntheticMemberSpec.syntheticConstructor(
        context.ownerFqn(),
        new MethodSignature(parameterTypes, VoidType.INSTANCE, parameterNames, List.of(), List.of(), Set.of("public")),
        context.locationOf().apply(annotation),
        Set.of("public"),
        SyntheticOrigin.TRANSFORM,
        InferenceConfidence.DETERMINISTIC));
  }

  private static boolean sameSignature(List<JvmType> propertyTypes,
                                       ConstructorNode constructor,
                                       TransformContext context) {
    if (constructor.getParameters().length != propertyTypes.size()) {
      return false;
    }
    for (int i = 0; i < constructor.getParameters().length; i++) {
      if (!propertyTypes.get(i).equals(context.typeOf().apply(constructor.getParameters()[i].getType()))) {
        return false;
      }
    }
    return true;
  }
}
