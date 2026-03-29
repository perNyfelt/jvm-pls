package se.alipsa.jvmpls.groovy.transforms;

import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import se.alipsa.jvmpls.core.model.InferenceConfidence;
import se.alipsa.jvmpls.core.model.SyntheticOrigin;
import se.alipsa.jvmpls.core.types.ClassType;
import se.alipsa.jvmpls.groovy.GroovyAnnotations;
import se.alipsa.jvmpls.groovy.SyntheticMemberSpec;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class LoggingTransformAnalyzer implements TransformAnalyzer {

  private static final Map<String, String> LOGGER_TYPES = Map.of(
      GroovyAnnotations.SLF4J, "org.slf4j.Logger",
      GroovyAnnotations.LOG, "java.util.logging.Logger",
      GroovyAnnotations.LOG4J, "org.apache.log4j.Logger",
      GroovyAnnotations.LOG4J2, "org.apache.logging.log4j.Logger",
      GroovyAnnotations.COMMONS, "org.apache.commons.logging.Log");

  @Override
  public List<SyntheticMemberSpec> analyze(ClassNode classNode, AnnotationNode annotation, TransformContext context) {
    String annotationName = annotation.getClassNode().getName();
    String loggerType = LOGGER_TYPES.get(annotationName);
    if (loggerType == null) {
      String simpleName = annotation.getClassNode().getNameWithoutPackage();
      for (Map.Entry<String, String> entry : LOGGER_TYPES.entrySet()) {
        if (entry.getKey().equals(annotationName) || entry.getKey().endsWith("." + simpleName)) {
          loggerType = entry.getValue();
          break;
        }
      }
    }
    if (loggerType == null) {
      return List.of();
    }
    return List.of(SyntheticMemberSpec.syntheticField(
        context.ownerFqn(),
        "log",
        new ClassType(loggerType, List.of()),
        context.locationOf().apply(annotation),
        Set.of("private", "static", "final"),
        SyntheticOrigin.LOG_FIELD,
        InferenceConfidence.DETERMINISTIC));
  }
}
