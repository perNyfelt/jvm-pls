package se.alipsa.jvmpls.groovy.transforms;

import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import se.alipsa.jvmpls.groovy.SyntheticMemberSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TransformRegistry {

  private final Map<String, TransformAnalyzer> analyzers = new LinkedHashMap<>();
  private final DelegateTransformAnalyzer delegateAnalyzer = new DelegateTransformAnalyzer();

  public TransformRegistry() {
    TransformAnalyzer builder = new BuilderTransformAnalyzer();
    TransformAnalyzer constructors = new ConstructorTransformAnalyzer();
    TransformAnalyzer logging = new LoggingTransformAnalyzer();
    analyzers.put("groovy.transform.builder.Builder", builder);
    analyzers.put("groovy.transform.TupleConstructor", constructors);
    analyzers.put("groovy.transform.Canonical", constructors);
    analyzers.put("groovy.transform.Immutable", constructors);
    analyzers.put("groovy.util.logging.Slf4j", logging);
    analyzers.put("groovy.util.logging.Log", logging);
    analyzers.put("groovy.util.logging.Log4j", logging);
    analyzers.put("groovy.util.logging.Log4j2", logging);
    analyzers.put("groovy.util.logging.Commons", logging);
  }

  public List<SyntheticMemberSpec> analyzeClass(ClassNode classNode, TransformContext context) {
    ArrayList<SyntheticMemberSpec> specs = new ArrayList<>();
    for (AnnotationNode annotation : classNode.getAnnotations()) {
      TransformAnalyzer analyzer = analyzerFor(annotation);
      if (analyzer != null) {
        specs.addAll(analyzer.analyze(classNode, annotation, context));
      }
    }
    for (FieldNode field : classNode.getFields()) {
      for (AnnotationNode annotation : field.getAnnotations()) {
        if (matches(annotation, "groovy.lang.Delegate")) {
          specs.addAll(delegateAnalyzer.analyze(classNode, field, context));
        }
      }
    }
    return specs;
  }

  private TransformAnalyzer analyzerFor(AnnotationNode annotation) {
    String annotationName = annotation.getClassNode().getName();
    TransformAnalyzer analyzer = analyzers.get(annotationName);
    if (analyzer != null) {
      return analyzer;
    }
    String simpleName = annotation.getClassNode().getNameWithoutPackage();
    for (Map.Entry<String, TransformAnalyzer> entry : analyzers.entrySet()) {
      String registeredName = entry.getKey();
      if (registeredName.equals(annotationName) || registeredName.endsWith("." + simpleName)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static boolean matches(AnnotationNode annotation, String fqName) {
    String annotationName = annotation.getClassNode().getName();
    return fqName.equals(annotationName) || fqName.endsWith("." + annotation.getClassNode().getNameWithoutPackage());
  }
}
