package test.alipsa.jvmpls.java;

import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.core.model.CompletionItem;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.server.CoreServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class JavaPluginCompletionsTest {

  @Test
  void completes_types_from_same_package_and_imports() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-java-complete1");

    Path apple = dir.resolve("Apple.java");
    String appleCode = "package demo; public class Apple {}";
    Files.writeString(apple, appleCode, StandardCharsets.UTF_8);
    String appleUri = apple.toUri().toString();

    Path banana = dir.resolve("Banana.java");
    String bananaCode = "package thing; public class Banana {}";
    Files.writeString(banana, bananaCode, StandardCharsets.UTF_8);
    String bananaUri = banana.toUri().toString();

    Path main = dir.resolve("Main.java");
    // NOTE: caret marker is placed *right after* 'Ba' with no space, so the prefix is "Ba"
    String mainCode = """
      package demo;
      import thing.Banana;
      class Main {
        void m() {
          Ba/*caret*/
        }
      }
      """;
    Files.writeString(main, mainCode, StandardCharsets.UTF_8);
    String mainUri = main.toUri().toString();

    try (CoreServer server = CoreServer.createDefault((u, d) -> {})) {
      // index all 3
      server.openFile(appleUri, appleCode);
      server.openFile(bananaUri, bananaCode);
      server.openFile(mainUri,   mainCode);

      Position pos = positionAtMarker(mainCode, "/*caret*/");
      List<CompletionItem> items = server.completions(mainUri, pos);

      assertTrue(containsLabel(items, "Banana"),
          "Expected 'Banana' from explicit single-type import");
      assertFalse(containsLabel(items, "Apple"),
          "Should not suggest 'Apple' for prefix 'Ba' from same package");
    }
  }

  @Test
  void completes_from_star_import_and_dotted_prefix() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-java-complete2");

    Path banana = dir.resolve("Banana.java");
    String bananaCode = "package thing; public class Banana {}";
    Files.writeString(banana, bananaCode, StandardCharsets.UTF_8);
    String bananaUri = banana.toUri().toString();

    Path main = dir.resolve("Main.java");
    String mainCode = """
    package demo;
    import thing.*;
    class Main {
      void m() {
        thing.Ba/*caret*/
      }
    }
    """;
    Files.writeString(main, mainCode, StandardCharsets.UTF_8);
    String mainUri = main.toUri().toString();

    // capture diags
    var diags = new ConcurrentHashMap<String, List<Diagnostic>>();
    try (CoreServer server = CoreServer.createDefault((uri, ds) ->
        diags.computeIfAbsent(uri, k -> new ArrayList<>()).addAll(ds))) {
      server.openFile(bananaUri, bananaCode);
      server.openFile(mainUri,   mainCode);

      // ASSERT: no diagnostics for a well-formed support file
      assertTrue(diags.getOrDefault(bananaUri, List.of()).isEmpty(),
          "Banana.java should index without diagnostics");

      // completions should still work
      Position pos = positionAtMarker(mainCode, "/*caret*/");
      var items = server.completions(mainUri, pos);
      assertTrue(containsLabel(items, "Banana"),
          "Expected 'Banana' when typing dotted prefix 'thing.Ba'");
    }
  }


  // ---------- helpers ----------

  private static boolean containsLabel(List<CompletionItem> items, String label) {
    if (items == null) return false;
    for (CompletionItem it : items) {
      // prefer getLabel(); fall back to toString() if your model differs
      try {
        if (label.equals(it.getLabel())) return true;
      } catch (Throwable ignore) {
        if (it != null && label.equals(String.valueOf(it))) return true;
      }
    }
    return false;
  }

  /** Convert the index of a marker to a Position (line/column), where Position points to the marker start. */
  private static Position positionAtMarker(String text, String marker) {
    int idx = text.indexOf(marker);
    if (idx < 0) throw new AssertionError("Marker not found: " + marker);
    int line = 0, col = 0;
    for (int i = 0; i < idx; i++) {
      char c = text.charAt(i);
      if (c == '\n') { line++; col = 0; } else { col++; }
    }
    return new Position(line, col);
  }
}
