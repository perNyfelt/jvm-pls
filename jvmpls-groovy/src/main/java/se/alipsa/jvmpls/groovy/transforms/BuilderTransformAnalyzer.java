package se.alipsa.jvmpls.groovy.transforms;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.PropertyNode;

import se.alipsa.jvmpls.core.model.InferenceConfidence;
import se.alipsa.jvmpls.core.model.SyntheticOrigin;
import se.alipsa.jvmpls.core.types.ClassType;
import se.alipsa.jvmpls.core.types.JvmType;
import se.alipsa.jvmpls.core.types.MethodSignature;
import se.alipsa.jvmpls.groovy.SyntheticMemberSpec;

final class BuilderTransformAnalyzer implements TransformAnalyzer {

  @Override
  public List<SyntheticMemberSpec> analyze(
      ClassNode classNode, AnnotationNode annotation, TransformContext context) {
    ArrayList<SyntheticMemberSpec> specs = new ArrayList<>();
    String pkg = context.packageName();
    String builderFqn = (pkg.isBlank() ? "" : pkg + ".") + context.simpleName() + "Builder";
    var origin = SyntheticOrigin.BUILDER;
    var confidence = InferenceConfidence.DETERMINISTIC;
    specs.add(
        SyntheticMemberSpec.syntheticClass(
            builderFqn, context.locationOf().apply(annotation), origin, confidence));
    specs.add(
        SyntheticMemberSpec.syntheticMethod(
            context.ownerFqn(),
            "builder",
            new MethodSignature(
                List.of(),
                new ClassType(builderFqn, List.of()),
                List.of(),
                List.of(),
                List.of(),
                Set.of("public", "static")),
            context.locationOf().apply(annotation),
            Set.of("public", "static"),
            origin,
            confidence));
    for (PropertyNode property : classNode.getProperties()) {
      JvmType propertyType = context.typeOf().apply(property.getType());
      specs.add(
          SyntheticMemberSpec.syntheticMethod(
              builderFqn,
              property.getName(),
              new MethodSignature(
                  List.of(propertyType),
                  new ClassType(builderFqn, List.of()),
                  List.of(property.getName()),
                  List.of(),
                  List.of(),
                  Set.of("public")),
              context.locationOf().apply(property),
              Set.of("public"),
              origin,
              confidence));
    }
    specs.add(
        SyntheticMemberSpec.syntheticMethod(
            builderFqn,
            "build",
            new MethodSignature(
                List.of(),
                new ClassType(context.ownerFqn(), List.of()),
                List.of(),
                List.of(),
                List.of(),
                Set.of("public")),
            context.locationOf().apply(annotation),
            Set.of("public"),
            origin,
            confidence));
    return specs;
  }
}
