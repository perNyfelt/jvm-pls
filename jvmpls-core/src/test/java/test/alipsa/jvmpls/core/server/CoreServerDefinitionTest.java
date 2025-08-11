package test.alipsa.jvmpls.core.server;

import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.server.CoreServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CoreServerDefinitionTest {

  @Test
  void definition_acrossFiles_findsHello() throws Exception {
    Path dir = Files.createTempDirectory("jvm-pls-def");
    // Define Hello.java (no package for simplicity)
    Path hello = dir.resolve("Hello.java");
    String helloCode = """
      public class Hello {
        void greet() { System.out.println("hi"); }
      }
      """;
    Files.writeString(hello, helloCode, StandardCharsets.UTF_8);
    String helloUri = hello.toUri().toString();

    // Use Hello in Main.java
    Path main = dir.resolve("Main.java");
    String mainCode = """
      public class Main {
        public static void main(String[] args) {
          Hello h = new Hello();
          h.greet();
        }
      }
      """;
    Files.writeString(main, mainCode, StandardCharsets.UTF_8);
    String mainUri = main.toUri().toString();

    try (CoreServer server = CoreServer.createDefault(CoreServerDefinitionTest::noop)) {
      // Index both files
      server.openFile(helloUri, helloCode);
      server.openFile(mainUri, mainCode);

      // Ask for definition at the "Hello" usage in "Hello h = new Hello();"
      Position pos = firstOccurrencePosition(mainCode, "Hello"); // the first "Hello" on that line
      Optional<Location> def = server.definition(mainUri, pos);

      assertTrue(def.isPresent(), "definition should be found");
      assertEquals(helloUri, def.get().getUri(), "definition should jump to Hello.java");
    }
  }

  private static void noop(String uri, java.util.List<se.alipsa.jvmpls.core.model.Diagnostic> diags) {
    // no-op publisher
  }

  private static Position firstOccurrencePosition(String text, String needle) {
    int idx = text.indexOf(needle);
    assertTrue(idx >= 0, "needle not found in text");
    int line = 0, col = 0;
    for (int i = 0; i < idx; i++) {
      char c = text.charAt(i);
      if (c == '\n') { line++; col = 0; } else { col++; }
    }
    return new Position(line, col);
  }
}
