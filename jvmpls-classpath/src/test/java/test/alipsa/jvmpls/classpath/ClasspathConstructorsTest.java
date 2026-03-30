package test.alipsa.jvmpls.classpath;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.classpath.ClasspathSymbolProviderFactory;
import se.alipsa.jvmpls.core.SymbolProvider;
import se.alipsa.jvmpls.core.SymbolProviderContext;

class ClasspathConstructorsTest {

  @Test
  void exposesConstructorsForJdkTypes() {
    ClasspathSymbolProviderFactory factory = new ClasspathSymbolProviderFactory();

    List<SymbolProvider> providers =
        factory.createProviders(
            new SymbolProviderContext(List.of(), Path.of(System.getProperty("java.home"))));

    SymbolProvider provider = providers.getFirst();
    assertFalse(provider.constructorsOf("java.lang.String").isEmpty());
    assertTrue(
        provider.constructorsOf("java.lang.String").stream()
            .anyMatch(symbol -> symbol.getFqName().startsWith("java.lang.String#<init>(")));
  }
}
