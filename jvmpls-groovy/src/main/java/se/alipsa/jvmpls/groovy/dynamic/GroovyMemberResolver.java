package se.alipsa.jvmpls.groovy.dynamic;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.SymbolInfo;
import se.alipsa.jvmpls.core.model.SyntheticOrigin;
import se.alipsa.jvmpls.core.types.JvmTypes;

public final class GroovyMemberResolver {

  private final CoreQuery core;
  private final BiFunction<String, Position, List<ScopedSyntheticMember>> scopedMembers;
  private final java.util.function.Function<String, List<String>> supertypes;
  private final Predicate<String> dynamicMethodType;
  private final Predicate<String> dynamicPropertyType;

  public GroovyMemberResolver(
      CoreQuery core,
      BiFunction<String, Position, List<ScopedSyntheticMember>> scopedMembers,
      java.util.function.Function<String, List<String>> supertypes,
      Predicate<String> dynamicMethodType,
      Predicate<String> dynamicPropertyType) {
    this.core = Objects.requireNonNull(core, "core");
    this.scopedMembers = Objects.requireNonNull(scopedMembers, "scopedMembers");
    this.supertypes = Objects.requireNonNull(supertypes, "supertypes");
    this.dynamicMethodType = Objects.requireNonNull(dynamicMethodType, "dynamicMethodType");
    this.dynamicPropertyType = Objects.requireNonNull(dynamicPropertyType, "dynamicPropertyType");
  }

  public List<SymbolInfo> membersAt(String fileUri, Position position, String receiverTypeFqn) {
    LinkedHashMap<String, SymbolInfo> results = new LinkedHashMap<>();
    collect(receiverTypeFqn, fileUri, position, results, new LinkedHashSet<>());
    return results.values().stream()
        .sorted(Comparator.comparingInt(GroovyMemberResolver::priority))
        .toList();
  }

  public boolean isDynamicMethodType(String receiverTypeFqn) {
    return receiverTypeFqn != null && dynamicMethodType.test(receiverTypeFqn);
  }

  public boolean isDynamicPropertyType(String receiverTypeFqn) {
    return receiverTypeFqn != null && dynamicPropertyType.test(receiverTypeFqn);
  }

  private void collect(
      String typeFqn,
      String fileUri,
      Position position,
      Map<String, SymbolInfo> results,
      Set<String> visited) {
    if (typeFqn == null || typeFqn.isBlank() || !visited.add(typeFqn)) {
      return;
    }
    for (SymbolInfo symbol : core.membersOf(typeFqn)) {
      results.putIfAbsent(identity(symbol), symbol);
    }
    for (ScopedSyntheticMember scoped : scopedMembers.apply(fileUri, position)) {
      if (typeFqn.equals(scoped.targetTypeFqn()) && scoped.isVisibleAt(position)) {
        results.putIfAbsent(identity(scoped.symbol()), scoped.symbol());
      }
    }
    for (String supertype : supertypes.apply(typeFqn)) {
      collect(supertype, fileUri, position, results, visited);
    }
  }

  private static int priority(SymbolInfo symbol) {
    if (symbol.getSyntheticOrigin() == SyntheticOrigin.NONE) {
      return 0;
    }
    return switch (symbol.getInferenceConfidence()) {
      case DETERMINISTIC -> 1;
      case HIGH -> 2;
      case LOW -> 3;
    };
  }

  private static String identity(SymbolInfo symbol) {
    return switch (symbol.getKind()) {
      case FIELD -> "FIELD:" + fieldName(symbol.getFqName());
      case METHOD -> "METHOD:" + methodName(symbol.getFqName()) + ":" + methodSignature(symbol);
      case CONSTRUCTOR -> "CTOR:" + symbol.getContainerFqName() + ":" + methodSignature(symbol);
      default -> symbol.getKind() + ":" + symbol.getFqName();
    };
  }

  private static String methodSignature(SymbolInfo symbol) {
    return symbol.getMethodSignature() == null
        ? symbol.getSignature()
        : JvmTypes.toLegacyMethodSignature(symbol.getMethodSignature());
  }

  private static String fieldName(String fqn) {
    int lastDot = fqn.lastIndexOf('.');
    return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
  }

  private static String methodName(String fqn) {
    int hash = fqn.lastIndexOf('#');
    if (hash < 0) {
      return fqn;
    }
    int open = fqn.indexOf('(', hash + 1);
    return open < 0 ? fqn.substring(hash + 1) : fqn.substring(hash + 1, open);
  }
}
