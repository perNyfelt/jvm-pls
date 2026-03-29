package se.alipsa.jvmpls.groovy.transforms;

import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import se.alipsa.jvmpls.core.model.InferenceConfidence;
import se.alipsa.jvmpls.core.model.SymbolInfo;
import se.alipsa.jvmpls.core.model.SyntheticOrigin;
import se.alipsa.jvmpls.core.types.ClassType;
import se.alipsa.jvmpls.core.types.MethodSignature;
import se.alipsa.jvmpls.groovy.SyntheticMemberSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class DelegateTransformAnalyzer {

  List<SyntheticMemberSpec> analyze(ClassNode owner, FieldNode delegateField, TransformContext context) {
    if (!(context.typeOf().apply(delegateField.getType()) instanceof ClassType delegateType)) {
      return List.of();
    }
    ArrayList<SyntheticMemberSpec> specs = new ArrayList<>();
    for (SymbolInfo symbol : context.core().membersOf(delegateType.fqName())) {
      if (symbol.getModifiers().contains("private")) {
        continue;
      }
      if (symbol.getModifiers().contains("static")) {
        continue;
      }
      if (symbol.getKind() == SymbolInfo.Kind.METHOD && symbol.getMethodSignature() != null) {
        specs.add(SyntheticMemberSpec.syntheticMethod(
            context.ownerFqn(),
            memberName(symbol),
            symbol.getMethodSignature(),
            context.locationOf().apply(delegateField),
            symbol.getModifiers(),
            SyntheticOrigin.DELEGATE,
            InferenceConfidence.DETERMINISTIC));
      } else if (symbol.getKind() == SymbolInfo.Kind.FIELD && symbol.getResolvedType() != null) {
        specs.add(SyntheticMemberSpec.syntheticField(
            context.ownerFqn(),
            memberName(symbol),
            symbol.getResolvedType(),
            context.locationOf().apply(delegateField),
            symbol.getModifiers(),
            SyntheticOrigin.DELEGATE,
            InferenceConfidence.DETERMINISTIC));
      }
    }
    return specs;
  }

  private static String memberName(SymbolInfo symbol) {
    String fqn = symbol.getFqName();
    int hash = fqn.lastIndexOf('#');
    if (hash >= 0) {
      int open = fqn.indexOf('(', hash + 1);
      return open < 0 ? fqn.substring(hash + 1) : fqn.substring(hash + 1, open);
    }
    int lastDot = fqn.lastIndexOf('.');
    return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
  }
}
