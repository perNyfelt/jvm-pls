package test.alipsa.jvmpls.groovy;

import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.core.model.CompletionItem;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.server.CoreServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class GroovyPluginCompletionsTest {

  @Test
  void completes_types_from_same_package_and_imports() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-groovy-complete1");

    Path apple = dir.resolve("Apple.groovy");
    String appleCode = "package demo\nclass Apple {}";
    Files.writeString(apple, appleCode, StandardCharsets.UTF_8);
    String appleUri = apple.toUri().toString();

    Path banana = dir.resolve("Banana.groovy");
    String bananaCode = "package thing\nclass Banana {}";
    Files.writeString(banana, bananaCode, StandardCharsets.UTF_8);
    String bananaUri = banana.toUri().toString();

    Path main = dir.resolve("Main.groovy");
    // caret goes right after "Ba" (prefix = "Ba")
    String mainCode = """
      package demo
      import thing.Banana
      class Main {
        static void run() {
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
    Path dir = Files.createTempDirectory("jvmpls-groovy-complete2");

    Path banana = dir.resolve("Banana.groovy");
    String bananaCode = "package thing\nclass Banana {}";
    Files.writeString(banana, bananaCode, StandardCharsets.UTF_8);
    String bananaUri = banana.toUri().toString();

    Path main = dir.resolve("Main.groovy");
    String mainCode = """
    package demo
    import thing.*
    class Main {
      static void run() {
        thing.Ba/*caret*/
      }
    }
    """;
    Files.writeString(main, mainCode, StandardCharsets.UTF_8);
    String mainUri = main.toUri().toString();

    // collect diagnostics per file
    var diags = new ConcurrentHashMap<String, List<Diagnostic>>();
    try (CoreServer server = CoreServer.createDefault((uri, ds) ->
        diags.computeIfAbsent(uri, k -> new ArrayList<>()).addAll(ds))) {
      server.openFile(bananaUri, bananaCode);
      server.openFile(mainUri,   mainCode);

      // Banana.groovy is well-formed, should yield no diagnostics
      assertTrue(diags.getOrDefault(bananaUri, List.of()).isEmpty(),
          "Banana.groovy should index without diagnostics");

      Position pos = positionAtMarker(mainCode, "/*caret*/");
      List<CompletionItem> items = server.completions(mainUri, pos);

      // We should get Banana when typing dotted prefix 'thing.Ba'
      CompletionItem bananaItem = byLabel(items, "Banana");
      assertNotNull(bananaItem, "Expected 'Banana' when typing dotted prefix 'thing.Ba'");

      // Because 'import thing.*' is present, there must be NO auto-import edit
      var edits = bananaItem.getAdditionalTextEdits();
      assertTrue(edits == null || edits.isEmpty(),
          "No auto-import edits expected when star import already makes Banana visible");
    }
  }

  @Test
  void completes_external_jdk_types_from_package_prefix() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-groovy-complete3");

    Path main = dir.resolve("Main.groovy");
    String mainCode = """
      package demo
      class Main {
        static void run() {
          java.util.Li/*caret*/
        }
      }
      """;
    Files.writeString(main, mainCode, StandardCharsets.UTF_8);
    String mainUri = main.toUri().toString();

    try (CoreServer server = CoreServer.createDefault((u, d) -> {})) {
      server.openFile(mainUri, mainCode);

      Position pos = positionAtMarker(mainCode, "/*caret*/");
      List<CompletionItem> items = server.completions(mainUri, pos);

      assertTrue(containsLabel(items, "List"),
          "Expected 'List' from external JDK package completion");
    }
  }

  @Test
  void completes_members_from_external_receiver_type() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-groovy-complete4");

    Path main = dir.resolve("Main.groovy");
    String mainCode = """
      package demo
      import java.util.List
      class Main {
        List<String> names = []
        void run() {
          names.ad/*caret*/
        }
      }
      """;
    Files.writeString(main, mainCode, StandardCharsets.UTF_8);
    String mainUri = main.toUri().toString();

    try (CoreServer server = CoreServer.createDefault((u, d) -> {})) {
      server.openFile(mainUri, mainCode);

      Position pos = positionAtMarker(mainCode, "/*caret*/");
      List<CompletionItem> items = server.completions(mainUri, pos);

      CompletionItem add = byLabel(items, "add");
      assertNotNull(add, "Expected external List member 'add' on List receiver");
      assertEquals("boolean", add.getTypeDetail());
    }
  }


  // ---------- helpers ----------

  private static boolean containsLabel(List<CompletionItem> items, String label) {
    if (items == null) return false;
    for (CompletionItem it : items) {
      try {
        if (label.equals(it.getLabel())) return true;
      } catch (Throwable ignore) {
        if (it != null && label.equals(String.valueOf(it))) return true;
      }
    }
    return false;
  }

  private static CompletionItem byLabel(List<CompletionItem> items, String label) {
    if (items == null) return null;
    for (CompletionItem it : items) {
      if (it != null && label.equals(it.getLabel())) return it;
    }
    return null;
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
