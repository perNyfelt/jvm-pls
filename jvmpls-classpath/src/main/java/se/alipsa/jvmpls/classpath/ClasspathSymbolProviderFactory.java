package se.alipsa.jvmpls.classpath;

import java.util.List;

import se.alipsa.jvmpls.core.SymbolProvider;
import se.alipsa.jvmpls.core.SymbolProviderContext;
import se.alipsa.jvmpls.core.SymbolProviderFactory;

public final class ClasspathSymbolProviderFactory implements SymbolProviderFactory {

  @Override
  public String id() {
    return "classpath";
  }

  @Override
  public List<SymbolProvider> createProviders(SymbolProviderContext context) {
    ClasspathScanner scanner = new ClasspathScanner();
    JdkIndex jdkIndex = new JdkIndex();
    BinaryTypeReader reader = new BinaryTypeReader();

    ScannedTypeCatalog catalog =
        ScannedTypeCatalog.builder()
            .merge(jdkIndex.scan(context.targetJdkHome()))
            .merge(scanner.scan(context.classpathEntries()))
            .build();

    if (catalog.isEmpty()) {
      return List.of();
    }
    return List.of(new ClasspathSymbolProvider(catalog, reader));
  }
}
