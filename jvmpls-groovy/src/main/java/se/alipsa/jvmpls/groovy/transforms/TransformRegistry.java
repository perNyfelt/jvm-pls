package se.alipsa.jvmpls.groovy.transforms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;

import se.alipsa.jvmpls.core.model.SymbolInfo;
import se.alipsa.jvmpls.core.types.JvmTypes;
import se.alipsa.jvmpls.groovy.GroovyAnnotations;
import se.alipsa.jvmpls.groovy.SyntheticMemberSpec;

public final class TransformRegistry {

  private final Map<String, TransformAnalyzer> analyzers = new LinkedHashMap<>();
  private final DelegateTransformAnalyzer delegateAnalyzer = new DelegateTransformAnalyzer();

  public TransformRegistry() {
    TransformAnalyzer builder = new BuilderTransformAnalyzer();
    TransformAnalyzer constructors = new ConstructorTransformAnalyzer();
    TransformAnalyzer logging = new LoggingTransformAnalyzer();
    analyzers.put(GroovyAnnotations.BUILDER, builder);
    analyzers.put(GroovyAnnotations.TUPLE_CONSTRUCTOR, constructors);
    analyzers.put(GroovyAnnotations.CANONICAL, constructors);
    analyzers.put(GroovyAnnotations.IMMUTABLE, constructors);
    analyzers.put(GroovyAnnotations.SLF4J, logging);
    analyzers.put(GroovyAnnotations.LOG, logging);
    analyzers.put(GroovyAnnotations.LOG4J, logging);
    analyzers.put(GroovyAnnotations.LOG4J2, logging);
    analyzers.put(GroovyAnnotations.COMMONS, logging);
  }

  public List<SyntheticMemberSpec> analyzeClass(ClassNode classNode, TransformContext context) {
    Objects.requireNonNull(classNode, "classNode");
    Objects.requireNonNull(context, "context");
    ArrayList<SyntheticMemberSpec> specs = new ArrayList<>();
    for (AnnotationNode annotation : classNode.getAnnotations()) {
      TransformAnalyzer analyzer = analyzerFor(annotation);
      if (analyzer != null) {
        specs.addAll(analyzer.analyze(classNode, annotation, context));
      }
    }
    for (FieldNode field : classNode.getFields()) {
      for (AnnotationNode annotation : field.getAnnotations()) {
        if (matches(annotation, GroovyAnnotations.DELEGATE)) {
          specs.addAll(delegateAnalyzer.analyze(field, context));
        }
      }
    }
    return dedupe(specs);
  }

  private TransformAnalyzer analyzerFor(AnnotationNode annotation) {
    if (annotation == null || annotation.getClassNode() == null) {
      return null;
    }
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
    if (annotation == null || annotation.getClassNode() == null) {
      return false;
    }
    String annotationName = annotation.getClassNode().getName();
    return fqName.equals(annotationName)
        || fqName.endsWith("." + annotation.getClassNode().getNameWithoutPackage());
  }

  private static List<SyntheticMemberSpec> dedupe(List<SyntheticMemberSpec> specs) {
    LinkedHashMap<String, SyntheticMemberSpec> deduped = new LinkedHashMap<>();
    for (SyntheticMemberSpec spec : specs) {
      deduped.putIfAbsent(identity(spec), spec);
    }
    return List.copyOf(deduped.values());
  }

  private static String identity(SyntheticMemberSpec spec) {
    return switch (spec.kind()) {
      case CLASS, INTERFACE, ENUM, ANNOTATION -> spec.kind() + ":" + spec.ownerFqn();
      case FIELD -> spec.kind() + ":" + spec.ownerFqn() + "." + spec.memberName();
      case METHOD ->
          spec.kind()
              + ":"
              + spec.ownerFqn()
              + "#"
              + spec.memberName()
              + JvmTypes.toLegacyMethodSignature(spec.methodSignature());
      case CONSTRUCTOR ->
          SymbolInfo.Kind.CONSTRUCTOR
              + ":"
              + spec.ownerFqn()
              + JvmTypes.toLegacyMethodSignature(spec.methodSignature());
      default -> spec.kind() + ":" + spec.ownerFqn() + ":" + spec.memberName();
    };
  }
}
