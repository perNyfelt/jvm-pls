package test.alipsa.jvmpls.groovy;

import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.server.CoreServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GroovyPluginDefinitionTest {

  @Test
  void definition_findsHelloAcrossFiles() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-groovy-def");

    Path hello = dir.resolve("Hello.groovy");
    String helloCode = """
      package demo
      class Hello {
        def greet() { println 'hi' }
      }
      """;
    Files.writeString(hello, helloCode, StandardCharsets.UTF_8);
    String helloUri = hello.toUri().toString();

    Path main = dir.resolve("Main.groovy");
    String mainCode = """
      package demo
      class Main {
        static void main(String[] args) {
          Hello h = new Hello()
          h.greet()
        }
      }
      """;
    Files.writeString(main, mainCode, StandardCharsets.UTF_8);
    String mainUri = main.toUri().toString();

    try (CoreServer server = CoreServer.createDefault((u, d) -> {})) {
      server.openFile(helloUri, helloCode);
      server.openFile(mainUri,  mainCode);

      Position pos = firstOccurrencePosition(mainCode, "Hello");
      Optional<Location> def = server.definition(mainUri, pos);

      assertTrue(def.isPresent(), "definition should be found");
      assertEquals(helloUri, def.get().getUri(), "should jump to Hello.groovy");
    }
  }

  @Test
  void definition_findsExternalJdkType() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-groovy-jdk-def");

    Path main = dir.resolve("Main.groovy");
    String mainCode = """
      package demo
      import java.util.List
      class Main {
        List names = []
      }
      """;
    Files.writeString(main, mainCode, StandardCharsets.UTF_8);
    String mainUri = main.toUri().toString();

    try (CoreServer server = CoreServer.createDefault((u, d) -> {})) {
      server.openFile(mainUri, mainCode);

      Position pos = firstOccurrencePosition(mainCode, "List");
      Optional<Location> def = server.definition(mainUri, pos);

      assertTrue(def.isPresent(), "definition for external JDK type should be found");
      assertTrue(def.get().getUri().contains("java/util/List"),
          "definition should point to the binary List class resource");
    }
  }

  private static Position firstOccurrencePosition(String text, String needle) {
    int idx = text.indexOf(needle);
    assertTrue(idx >= 0, "needle not found");
    int line = 0, col = 0;
    for (int i = 0; i < idx; i++) {
      char c = text.charAt(i);
      if (c == '\n') { line++; col = 0; } else { col++; }
    }
    return new Position(line, col);
  }
}
