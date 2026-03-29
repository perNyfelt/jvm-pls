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
      results.putIfAbsent(symbol.getFqName(), symbol);
    }
    if (owner.superclassFqName() != null && !owner.superclassFqName().isBlank()) {
      catalog.findByFqn(owner.superclassFqName()).ifPresent(parent -> collectMembers(parent, results, visited));
    }
    for (String interfaceFqName : owner.interfaceFqNames()) {
      catalog.findByFqn(interfaceFqName).ifPresent(parent -> collectMembers(parent, results, visited));
    }
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
