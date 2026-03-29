package test.alipsa.jvmpls.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.model.CompletionItem;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.server.CoreServer;

import io.github.classgraph.ClassGraph;

class JavaGroovyExternalTypesIT {

  @Test
  void resolves_external_jdk_types_in_java_and_groovy() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-it-external");

    Path javaFile = dir.resolve("Main.java");
    String javaCode =
        """
        package demo;
        import java.util.List;
        public class Main {
          List<String> names;
          void complete() {
            java.util.Li/*caret-java*/
          }
        }
        """;
    Files.writeString(javaFile, javaCode, StandardCharsets.UTF_8);
    String javaUri = javaFile.toUri().toString();

    Path groovyFile = dir.resolve("Main.groovy");
    String groovyCode =
        """
        package demo
        import java.util.Map
        class MainGroovy {
          Map names = [:]
          static void complete() {
            java.util.Ma/*caret-groovy*/
          }
        }
        """;
    Files.writeString(groovyFile, groovyCode, StandardCharsets.UTF_8);
    String groovyUri = groovyFile.toUri().toString();

    try (CoreServer server = CoreServer.createDefault((u, d) -> {}, List.of(), currentJdkHome())) {
      server.openFile(javaUri, javaCode);
      server.openFile(groovyUri, groovyCode);

      Optional<Location> javaDefinition =
          server.definition(javaUri, firstWholeWord(javaCode, "List"));
      Optional<Location> groovyDefinition =
          server.definition(groovyUri, firstWholeWord(groovyCode, "Map"));
      List<CompletionItem> javaCompletions =
          server.completions(javaUri, positionAtMarker(javaCode, "/*caret-java*/"));
      List<CompletionItem> groovyCompletions =
          server.completions(groovyUri, positionAtMarker(groovyCode, "/*caret-groovy*/"));

      assertTrue(javaDefinition.isPresent(), "Java should resolve external JDK List");
      assertTrue(
          javaDefinition.get().getUri().contains("java/util/List"),
          "Java definition should point to the List class resource");
      assertTrue(groovyDefinition.isPresent(), "Groovy should resolve external JDK Map");
      assertTrue(
          groovyDefinition.get().getUri().contains("java/util/Map"),
          "Groovy definition should point to the Map class resource");
      assertTrue(
          containsLabel(javaCompletions, "List"),
          "Java completions should contain List from the JDK");
      assertTrue(
          containsLabel(groovyCompletions, "Map"),
          "Groovy completions should contain Map from the JDK");
    }
  }

  @Test
  void createDefault_does_not_scan_the_process_classpath() throws Exception {
    Path classGraphJar =
        Path.of(ClassGraph.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    String code =
        """
        package demo;
        import io.github.classgraph.ClassGraph;
        public class Main {
          ClassGraph graph;
        }
        """;
    Path dir = Files.createTempDirectory("jvmpls-it-no-runtime-classpath");
    Path javaFile = dir.resolve("Main.java");
    Files.writeString(javaFile, code, StandardCharsets.UTF_8);
    String javaUri = javaFile.toUri().toString();

    try (CoreServer defaultServer = CoreServer.createDefault((u, d) -> {})) {
      defaultServer.openFile(javaUri, code);
      assertFalse(
          defaultServer.definition(javaUri, firstWholeWord(code, "ClassGraph")).isPresent(),
          "default server should not resolve process classpath dependencies");
    }

    try (CoreServer explicitServer =
        CoreServer.createDefault(
            (u, d) -> {}, List.of(classGraphJar.toString()), currentJdkHome())) {
      explicitServer.openFile(javaUri, code);
      assertTrue(
          explicitServer.definition(javaUri, firstWholeWord(code, "ClassGraph")).isPresent(),
          "explicit classpath should resolve requested dependencies");
    }
  }

  private static boolean containsLabel(List<CompletionItem> items, String label) {
    return items.stream().anyMatch(item -> label.equals(item.getLabel()));
  }

  private static Position firstWholeWord(String text, String word) {
    var pattern =
        java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(word) + "\\b");
    var matcher = pattern.matcher(text);
    if (!matcher.find()) throw new AssertionError("word not found: " + word);
    return offsetToPosition(text, matcher.start());
  }

  private static Position positionAtMarker(String text, String marker) {
    int idx = text.indexOf(marker);
    if (idx < 0) throw new AssertionError("marker not found: " + marker);
    return offsetToPosition(text, idx);
  }

  private static Position offsetToPosition(String text, int idx) {
    int line = 0;
    int col = 0;
    for (int i = 0; i < idx; i++) {
      char c = text.charAt(i);
      if (c == '\n') {
        line++;
        col = 0;
      } else {
        col++;
      }
    }
    return new Position(line, col);
  }

  private static Path currentJdkHome() {
    return Path.of(System.getProperty("java.home"));
  }
}
