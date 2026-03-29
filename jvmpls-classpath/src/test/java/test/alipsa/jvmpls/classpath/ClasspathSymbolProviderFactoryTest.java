package test.alipsa.jvmpls.classpath;

import io.github.classgraph.ClassGraph;
import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.classpath.ClasspathSymbolProviderFactory;
import se.alipsa.jvmpls.core.SymbolProvider;
import se.alipsa.jvmpls.core.SymbolProviderContext;
import se.alipsa.jvmpls.core.model.SymbolInfo;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

  @Test
  void rescans_classpath_entries_when_directory_contents_change() throws Exception {
    Path sourceDir = Files.createTempDirectory("jvmpls-classpath-src");
    Path outputDir = Files.createTempDirectory("jvmpls-classpath-out");
    ClasspathSymbolProviderFactory factory = new ClasspathSymbolProviderFactory();

    compileType(sourceDir, outputDir, "First");
    List<SymbolProvider> firstProviders = factory.createProviders(
        new SymbolProviderContext(List.of(outputDir.toString()), null));

    assertEquals(1, firstProviders.size(), "factory should create one provider");
    assertTrue(firstProviders.getFirst().findByFqn("demo.First").isPresent());
    assertFalse(firstProviders.getFirst().findByFqn("demo.Second").isPresent());

    Files.deleteIfExists(outputDir.resolve("demo/First.class"));
    compileType(sourceDir, outputDir, "Second");
    List<SymbolProvider> secondProviders = factory.createProviders(
        new SymbolProviderContext(List.of(outputDir.toString()), null));

    assertEquals(1, secondProviders.size(), "factory should create one provider after recompilation");
    assertFalse(secondProviders.getFirst().findByFqn("demo.First").isPresent(),
        "provider should not retain stale classpath entries");
    assertTrue(secondProviders.getFirst().findByFqn("demo.Second").isPresent(),
        "provider should rescan updated classpath directories");
  }

  @Test
  void exposes_binary_members_and_inherited_members() {
    ClasspathSymbolProviderFactory factory = new ClasspathSymbolProviderFactory();

    List<SymbolProvider> providers = factory.createProviders(
        new SymbolProviderContext(List.of(), Path.of(System.getProperty("java.home"))));

    SymbolProvider provider = providers.getFirst();
    List<SymbolInfo> listMembers = provider.membersOf("java.util.List");
    List<SymbolInfo> integerMembers = provider.membersOf("java.lang.Integer");

    assertTrue(listMembers.stream().anyMatch(symbol -> "java.util.List#add(java.lang.Object)boolean".equals(symbol.getFqName())),
        "binary provider should expose declared methods");
    assertTrue(listMembers.stream().anyMatch(symbol -> symbol.getFqName().startsWith("java.util.Collection#stream(")),
        "binary provider should expose inherited interface members");
    assertEquals(1, listMembers.stream()
            .filter(symbol -> "add".equals(methodName(symbol)) && "(java.lang.Object)boolean".equals(symbol.getSignature()))
            .count(),
        "binary provider should keep the nearest declaration when inherited methods share a signature");
    assertTrue(integerMembers.stream().anyMatch(symbol -> "java.lang.Integer.MAX_VALUE".equals(symbol.getFqName())),
        "binary provider should expose fields");
  }

  private static String methodName(SymbolInfo symbol) {
    String fqn = symbol.getFqName();
    int hash = fqn.lastIndexOf('#');
    if (hash < 0) {
      return fqn;
    }
    int open = fqn.indexOf('(', hash + 1);
    return open < 0 ? fqn.substring(hash + 1) : fqn.substring(hash + 1, open);
  }

  private static void compileType(Path sourceDir, Path outputDir, String simpleName) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("system java compiler not available");
    }

    Path sourceFile = sourceDir.resolve(simpleName + ".java");
    Files.writeString(sourceFile, """
        package demo;
        public class %s {}
        """.formatted(simpleName), StandardCharsets.UTF_8);

    int result = compiler.run(null, null, null,
        "-d", outputDir.toString(),
        sourceFile.toString());
    assertEquals(0, result, "compilation should succeed");
  }
}
