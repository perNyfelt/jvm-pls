package se.alipsa.jvmpls.classpath;

import se.alipsa.jvmpls.core.SymbolProvider;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.Range;
import se.alipsa.jvmpls.core.model.SymbolInfo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ClasspathSymbolProvider implements SymbolProvider {

  private static final Range ZERO_RANGE = new Range(new Position(0, 0), new Position(0, 1));

  private final ScannedTypeCatalog catalog;
  private final BinaryTypeReader reader;
  private final ConcurrentMap<String, SymbolInfo> materialized = new ConcurrentHashMap<>();

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
          details.typeParameters());
    });
  }
}
