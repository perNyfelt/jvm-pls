package se.alipsa.jvmpls.groovy.transforms;

import org.codehaus.groovy.ast.FieldNode;
import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.model.InferenceConfidence;
import se.alipsa.jvmpls.core.model.SymbolInfo;
import se.alipsa.jvmpls.core.model.SyntheticOrigin;
import se.alipsa.jvmpls.core.types.ClassType;
import se.alipsa.jvmpls.groovy.SyntheticMemberSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class DelegateTransformAnalyzer {

  List<SyntheticMemberSpec> analyze(FieldNode delegateField, TransformContext context) {
    if (!(context.typeOf().apply(delegateField.getType()) instanceof ClassType delegateType)) {
      return List.of();
    }
    ArrayList<SyntheticMemberSpec> specs = new ArrayList<>();
    for (SymbolInfo symbol : context.core().membersOf(delegateType.fqName())) {
      if (!isVisible(symbol, context.ownerFqn(), delegateType.fqName(), context.core())) {
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

  private static boolean isVisible(SymbolInfo member, String currentOwner, String receiverType, CoreQuery core) {
    Set<String> modifiers = member.getModifiers() == null ? Set.of() : member.getModifiers();
    if (member.getKind() == SymbolInfo.Kind.CONSTRUCTOR || modifiers.contains("static")) {
      return false;
    }
    if (modifiers.contains("public")) {
      return true;
    }
    if (modifiers.contains("private")) {
      return false;
    }
    String declaringType = member.getContainerFqName();
    if (modifiers.contains("package-private")) {
      return samePackage(currentOwner, declaringType);
    }
    if (modifiers.contains("protected")) {
      return samePackage(currentOwner, declaringType)
          || (isSubtypeOf(currentOwner, declaringType, core) && isSubtypeOf(receiverType, currentOwner, core));
    }
    return true;
  }

  private static boolean samePackage(String left, String right) {
    return packageName(left).equals(packageName(right));
  }

  private static String packageName(String typeName) {
    if (typeName == null || typeName.isBlank()) {
      return "";
    }
    int lastDot = typeName.lastIndexOf('.');
    return lastDot < 0 ? "" : typeName.substring(0, lastDot);
  }

  private static boolean isSubtypeOf(String candidate, String target, CoreQuery core) {
    return isSubtypeOf(candidate, target, core, new java.util.LinkedHashSet<>());
  }

  private static boolean isSubtypeOf(String candidate, String target, CoreQuery core, Set<String> visited) {
    if (candidate == null || target == null || candidate.isBlank() || target.isBlank()) {
      return false;
    }
    if (!visited.add(candidate)) {
      return false;
    }
    if (candidate.equals(target)) {
      return true;
    }
    for (String supertype : core.supertypesOf(candidate)) {
      if (isSubtypeOf(supertype, target, core, visited)) {
        return true;
      }
    }
    return false;
  }
}
