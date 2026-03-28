package se.alipsa.jvmpls.classpath;

import se.alipsa.jvmpls.core.SymbolProvider;
import se.alipsa.jvmpls.core.SymbolProviderContext;
import se.alipsa.jvmpls.core.SymbolProviderFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ClasspathSymbolProviderFactory implements SymbolProviderFactory {

  private static final ConcurrentMap<String, ScannedTypeCatalog> CLASSPATH_CACHE = new ConcurrentHashMap<>();
  private static final ConcurrentMap<String, ScannedTypeCatalog> JDK_CACHE = new ConcurrentHashMap<>();

  @Override
  public String id() {
    return "classpath";
  }

  @Override
  public List<SymbolProvider> createProviders(SymbolProviderContext context) {
    ClasspathScanner scanner = new ClasspathScanner();
    JdkIndex jdkIndex = new JdkIndex();
    BinaryTypeReader reader = new BinaryTypeReader();

    ScannedTypeCatalog catalog = ScannedTypeCatalog.builder()
        .merge(JDK_CACHE.computeIfAbsent(jdkKey(context.targetJdkHome()),
            ignored -> jdkIndex.scan(context.targetJdkHome())))
        .merge(CLASSPATH_CACHE.computeIfAbsent(classpathKey(context.classpathEntries()),
            ignored -> scanner.scan(context.classpathEntries())))
        .build();

    if (catalog.isEmpty()) {
      return List.of();
    }
    return List.of(new ClasspathSymbolProvider(catalog, reader));
  }

  private static String classpathKey(List<String> classpathEntries) {
    return String.join(java.io.File.pathSeparator, classpathEntries);
  }

  private static String jdkKey(Path targetJdkHome) {
    return targetJdkHome == null ? "runtime" : targetJdkHome.normalize().toString();
  }
}
