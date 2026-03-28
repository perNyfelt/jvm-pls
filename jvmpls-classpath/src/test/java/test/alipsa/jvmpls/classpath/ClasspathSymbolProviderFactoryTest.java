package test.alipsa.jvmpls.classpath;

import io.github.classgraph.ClassGraph;
import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.classpath.ClasspathSymbolProviderFactory;
import se.alipsa.jvmpls.core.SymbolProvider;
import se.alipsa.jvmpls.core.SymbolProviderContext;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathSymbolProviderFactoryTest {

  @Test
  void resolvesJdkTypesFromCurrentRuntime() {
    ClasspathSymbolProviderFactory factory = new ClasspathSymbolProviderFactory();

    List<SymbolProvider> providers = factory.createProviders(
        new SymbolProviderContext(List.of(), Path.of(System.getProperty("java.home"))));

    assertFalse(providers.isEmpty(), "factory should provide a JDK symbol provider");
    assertTrue(providers.getFirst().findByFqn("java.util.List").isPresent());
  }

  @Test
  void resolvesTypesFromExplicitClasspathEntries() throws Exception {
    Path classGraphJar = Path.of(ClassGraph.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    ClasspathSymbolProviderFactory factory = new ClasspathSymbolProviderFactory();

    List<SymbolProvider> providers = factory.createProviders(
        new SymbolProviderContext(List.of(classGraphJar.toString()), null));

    assertFalse(providers.isEmpty(), "factory should provide a classpath symbol provider");
    assertTrue(providers.getFirst().findByFqn("io.github.classgraph.ClassGraph").isPresent());
    assertTrue(providers.getFirst().allInPackage("io.github.classgraph").stream()
        .anyMatch(symbol -> "io.github.classgraph.ClassGraph".equals(symbol.getFqName())));
  }
}
