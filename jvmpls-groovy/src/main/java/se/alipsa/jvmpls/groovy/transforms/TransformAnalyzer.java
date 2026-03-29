package se.alipsa.jvmpls.groovy.transforms;

import java.util.List;

import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;

import se.alipsa.jvmpls.groovy.SyntheticMemberSpec;

public interface TransformAnalyzer {
  List<SyntheticMemberSpec> analyze(
      ClassNode classNode, AnnotationNode annotation, TransformContext context);
}
