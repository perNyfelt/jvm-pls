package test.alipsa.jvmpls.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.CoreEngine;
import se.alipsa.jvmpls.core.CoreQuery;
import se.alipsa.jvmpls.core.DependencyGraph;
import se.alipsa.jvmpls.core.DocumentStore;
import se.alipsa.jvmpls.core.JvmLangPlugin;
import se.alipsa.jvmpls.core.PluginEnvironment;
import se.alipsa.jvmpls.core.PluginRegistry;
import se.alipsa.jvmpls.core.SymbolIndex;
import se.alipsa.jvmpls.core.SymbolReporter;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.Range;

class CoreEngineInvalidationTest {

  @Test
  void bodyOnlyChangesDoNotReindexDependentsButApiChangesDo() {
    SymbolIndex index = new SymbolIndex();
    PluginRegistry registry = new PluginRegistry(new TestPluginEnvironment(index, Runnable::run));
    CountingPlugin plugin = new CountingPlugin();
    registry.register(plugin);
    CoreEngine engine =
        new CoreEngine(registry, index, new DocumentStore(), new DependencyGraph(), Runnable::run);

    String dependentUri = "file:///A.fake";
    String dependencyUri = "file:///B.fake";

    engine.openFile(
        dependentUri,
        """
        class:test.A
        depends:file:///B.fake
        method:use()void
        body:v1
        """);
    engine.openFile(
        dependencyUri,
        """
        class:test.B
        method:api()void
        body:v1
        """);

    engine.changeFile(
        dependencyUri,
        """
        class:test.B
        method:api()void
        body:v2
        """);
    assertEquals(1, plugin.indexCount(dependentUri));
    assertEquals(2, plugin.indexCount(dependencyUri));

    engine.changeFile(
        dependencyUri,
        """
        class:test.B
        method:api()int
        body:v3
        """);
    assertEquals(2, plugin.indexCount(dependentUri));
    assertEquals(3, plugin.indexCount(dependencyUri));
  }

  private static final class CountingPlugin implements JvmLangPlugin {
    private final Map<String, Integer> indexCounts = new ConcurrentHashMap<>();

    @Override
    public String id() {
      return "test-fake";
    }

    @Override
    public Set<String> fileExtensions() {
      return Set.of("fake");
    }

    @Override
    public List<Diagnostic> index(String fileUri, String content, SymbolReporter reporter) {
      indexCounts.merge(fileUri, 1, Integer::sum);
      String owner = null;
      for (String line : content.split("\\R")) {
        if (line.startsWith("class:")) {
          owner = line.substring("class:".length()).trim();
          reporter.reportClass(owner, location(fileUri), false, false, false);
        } else if (line.startsWith("method:") && owner != null) {
          String signature = line.substring("method:".length()).trim();
          int open = signature.indexOf('(');
          reporter.reportMethod(
              owner, signature.substring(0, open), signature.substring(open), location(fileUri));
        } else if (line.startsWith("depends:")) {
          reporter.reportDependency(line.substring("depends:".length()).trim());
        }
      }
      return List.of();
    }

    int indexCount(String uri) {
      return indexCounts.getOrDefault(uri, 0);
    }

    private static Location location(String uri) {
      return new Location(uri, new Range(new Position(0, 0), new Position(0, 1)));
    }
  }

  private record TestPluginEnvironment(CoreQuery core, Executor executor)
      implements PluginEnvironment {
    @Override
    public List<String> classpath() {
      return List.of();
    }

    @Override
    public void log(String level, String message, Throwable t) {}
  }
}
