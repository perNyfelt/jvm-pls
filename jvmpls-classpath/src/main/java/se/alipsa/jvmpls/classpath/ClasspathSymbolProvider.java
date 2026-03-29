package se.alipsa.jvmpls.classpath;

import se.alipsa.jvmpls.core.SymbolProvider;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.Range;
import se.alipsa.jvmpls.core.model.SymbolInfo;
import se.alipsa.jvmpls.core.types.ClassType;
import se.alipsa.jvmpls.core.types.JvmType;
import se.alipsa.jvmpls.core.types.JvmTypes;
import se.alipsa.jvmpls.core.types.MethodSignature;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ClasspathSymbolProvider implements SymbolProvider {

  private static final Range ZERO_RANGE = new Range(new Position(0, 0), new Position(0, 1));

  private final ScannedTypeCatalog catalog;
  private final BinaryTypeReader reader;
  private final ConcurrentMap<String, SymbolInfo> materialized = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, List<SymbolInfo>> materializedMembers = new ConcurrentHashMap<>();

  public ClasspathSymbolProvider(ScannedTypeCatalog catalog, BinaryTypeReader reader) {
    this.catalog = catalog;
    this.reader = reader;
  }

  @Override
  public Optional<SymbolInfo> findByFqn(String fqn) {
    return catalog.findByFqn(fqn).map(this::materialize);
  }

  @Override
  public List<SymbolInfo> findBySimpleName(String simpleName) {
    return catalog.findBySimpleName(simpleName).stream()
        .map(this::materialize)
        .toList();
  }

  @Override
  public List<SymbolInfo> allInPackage(String pkgFqn) {
    return catalog.allInPackage(pkgFqn).stream()
        .map(this::materialize)
        .toList();
  }

  @Override
  public List<SymbolInfo> membersOf(String ownerFqn) {
    return materializedMembers.computeIfAbsent(ownerFqn, this::materializeMembers);
  }

  @Override
  public List<String> supertypesOf(String typeFqn) {
    return catalog.findByFqn(typeFqn)
        .map(descriptor -> {
          LinkedHashSet<String> supertypes = new LinkedHashSet<>();
          if (descriptor.superclassFqName() != null && !descriptor.superclassFqName().isBlank()) {
            supertypes.add(descriptor.superclassFqName());
          }
          supertypes.addAll(descriptor.interfaceFqNames());
          return List.copyOf(supertypes);
        })
        .orElseGet(List::of);
  }

  private SymbolInfo materialize(ScannedTypeDescriptor descriptor) {
    return materialized.computeIfAbsent(descriptor.fqName(), ignored -> {
      BinaryTypeDetails details = reader.read(descriptor.resourceUri());
      return new SymbolInfo(
          "binary",
          descriptor.kind(),
          descriptor.fqName(),
          descriptor.containerFqName(),
          new Location(descriptor.resourceUri(), ZERO_RANGE),
          details.signature(),
          details.modifiers(),
          details.typeParameters(),
          new ClassType(descriptor.fqName(), List.of()),
          null);
    });
  }

  private List<SymbolInfo> materializeMembers(String ownerFqn) {
    Optional<ScannedTypeDescriptor> owner = catalog.findByFqn(ownerFqn);
    if (owner.isEmpty()) {
      return List.of();
    }
    LinkedHashMap<String, SymbolInfo> results = new LinkedHashMap<>();
    collectMembers(owner.get(), results, new LinkedHashSet<>());
    return List.copyOf(results.values());
  }

  private void collectMembers(ScannedTypeDescriptor owner,
                              LinkedHashMap<String, SymbolInfo> results,
                              Set<String> visited) {
    if (!visited.add(owner.fqName())) {
      return;
    }
    BinaryTypeDetails details = reader.read(owner.resourceUri());
    for (BinaryMemberDetails member : details.members()) {
      if ("<init>".equals(member.name())) {
        continue;
      }
      SymbolInfo symbol = materializeMember(owner, member);
      results.putIfAbsent(memberIdentity(symbol), symbol);
    }
    if (owner.superclassFqName() != null && !owner.superclassFqName().isBlank()) {
      catalog.findByFqn(owner.superclassFqName()).ifPresent(parent -> collectMembers(parent, results, visited));
    }
    for (String interfaceFqName : owner.interfaceFqNames()) {
      catalog.findByFqn(interfaceFqName).ifPresent(parent -> collectMembers(parent, results, visited));
    }
  }

  private static String memberIdentity(SymbolInfo symbol) {
    return switch (symbol.getKind()) {
      case FIELD -> "FIELD:" + fieldName(symbol.getFqName());
      case METHOD -> "METHOD:" + methodName(symbol.getFqName()) + ":" +
          (symbol.getMethodSignature() == null ? symbol.getSignature() : JvmTypes.toLegacyMethodSignature(symbol.getMethodSignature()));
      default -> symbol.getKind() + ":" + symbol.getFqName();
    };
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

  private SymbolInfo materializeMember(ScannedTypeDescriptor owner, BinaryMemberDetails member) {
    Location location = new Location(owner.resourceUri(), ZERO_RANGE);
    if (member.kind() == SymbolInfo.Kind.FIELD) {
      JvmType resolvedType = JvmTypes.fromDescriptor(member.descriptor());
      return new SymbolInfo(
          "binary",
          SymbolInfo.Kind.FIELD,
          owner.fqName() + "." + member.name(),
          owner.fqName(),
          location,
          resolvedType.displayName(),
          member.modifiers(),
          List.of(),
          resolvedType,
          null);
    }
    MethodSignature methodSignature = JvmTypes.fromMethodDescriptor(
        member.descriptor(), List.of(), member.exceptions(), member.modifiers());
    String legacy = JvmTypes.toLegacyMethodSignature(methodSignature);
    return new SymbolInfo(
        "binary",
        SymbolInfo.Kind.METHOD,
        owner.fqName() + "#" + member.name() + legacy,
        owner.fqName(),
        location,
        legacy,
        member.modifiers(),
        List.of(),
        null,
        methodSignature);
  }
}
