package se.alipsa.jvmpls.classpath;

import java.util.List;
import java.util.logging.Logger;

import se.alipsa.jvmpls.core.SymbolProvider;
import se.alipsa.jvmpls.core.SymbolProviderContext;
import se.alipsa.jvmpls.core.SymbolProviderFactory;

public final class ClasspathSymbolProviderFactory implements SymbolProviderFactory {
  private static final Logger LOG =
      Logger.getLogger(ClasspathSymbolProviderFactory.class.getName());

  @Override
  public String id() {
    return "classpath";
  }

  @Override
  public List<SymbolProvider> createProviders(SymbolProviderContext context) {
    ClasspathScanner scanner = new ClasspathScanner();
    JdkIndex jdkIndex = new JdkIndex();
    BinaryTypeReader reader = new BinaryTypeReader();

    // Try loading from persistent cache first
    ClasspathCache cache =
        context.workspaceRoot() != null ? new ClasspathCache(context.workspaceRoot()) : null;
    ScannedTypeCatalog catalog = null;

    if (cache != null) {
      catalog = cache.load(context.classpathEntries());
      if (catalog != null) {
        LOG.info("Using cached classpath catalog");
      }
    }

    if (catalog == null) {
      catalog =
          ScannedTypeCatalog.builder()
              .merge(jdkIndex.scan(context.targetJdkHome()))
              .merge(scanner.scan(context.classpathEntries()))
              .build();

      // Save to cache for next startup
      if (cache != null && !catalog.isEmpty()) {
        cache.save(catalog, context.classpathEntries());
      }
    }

    if (catalog.isEmpty()) {
      return List.of();
    }
    return List.of(new ClasspathSymbolProvider(catalog, reader));
  }
}
