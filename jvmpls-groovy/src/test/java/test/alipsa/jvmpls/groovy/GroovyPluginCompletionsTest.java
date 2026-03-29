package test.alipsa.jvmpls.groovy;

import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.core.model.CompletionItem;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.server.CoreServer;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
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

  @Test
  void resolves_star_import_members_without_taking_first_package_blindly() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-groovy-complete5");

    Path main = dir.resolve("Main.groovy");
    String mainCode = """
      package demo
      import java.io.*
      import java.util.*
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
      CompletionItem add = byLabel(server.completions(mainUri, pos), "add");

      assertNotNull(add, "Expected List member completion even with multiple star imports");
      assertEquals("boolean", add.getTypeDetail());
    }
  }

  @Test
  void hides_inaccessible_binary_members_and_dedupes_overridden_members() throws Exception {
    Path sourceDir = Files.createTempDirectory("jvmpls-groovy-complete6-src");
    Path outputDir = Files.createTempDirectory("jvmpls-groovy-complete6-out");
    compileJavaSource(sourceDir, outputDir, "demo.Api", """
        package demo;
        public class Api {
          public void open() {}
          void internal() {}
          protected void subOnly() {}
        }
        """);

    Path main = sourceDir.resolve("Main.groovy");
    String mainCode = """
      package other
      import demo.Api
      import java.util.List
      class Main {
        Api api
        List<String> names = []
        void run() {
          api./*api*/
          names.add/*list*/
        }
      }
      """;
    Files.writeString(main, mainCode, StandardCharsets.UTF_8);
    String mainUri = main.toUri().toString();

    try (CoreServer server = CoreServer.createDefault((u, d) -> {},
        List.of(outputDir.toString()),
        Path.of(System.getProperty("java.home")))) {
      server.openFile(mainUri, mainCode);

      List<CompletionItem> apiItems = server.completions(mainUri, positionAtMarker(mainCode, "/*api*/"));
      assertTrue(containsLabel(apiItems, "open"), "Expected public binary member");
      assertFalse(containsLabel(apiItems, "internal"), "Package-private member should not be visible across packages");
      assertFalse(containsLabel(apiItems, "subOnly"), "Protected member should not be visible to unrelated classes");

      List<CompletionItem> listItems = server.completions(mainUri, positionAtMarker(mainCode, "/*list*/"));
      long addCount = listItems.stream().filter(item -> "add".equals(item.getLabel())).count();
      assertEquals(2, addCount, "Expected exactly the two List.add overloads without inherited duplicates");
    }
  }

  @Test
  void shows_protected_members_to_cross_package_subclasses() throws Exception {
    Path sourceDir = Files.createTempDirectory("jvmpls-groovy-complete7-src");
    Path outputDir = Files.createTempDirectory("jvmpls-groovy-complete7-out");
    compileJavaSource(sourceDir, outputDir, "demo.Base", """
        package demo;
        public class Base {
          protected void protectedMethod() {}
        }
        """);
    compileJavaSource(sourceDir, outputDir, "other.Sub", """
        package other;
        import demo.Base;
        public class Sub extends Base {}
        """);

    Path main = sourceDir.resolve("other/Consumer.groovy");
    Files.createDirectories(main.getParent());
    String mainCode = """
      package other
      class Consumer extends Sub {
        Consumer sibling
        void run() {
          sibling.pro/*caret*/
        }
      }
      """;
    Files.writeString(main, mainCode, StandardCharsets.UTF_8);
    String mainUri = main.toUri().toString();

    try (CoreServer server = CoreServer.createDefault((u, d) -> {},
        List.of(outputDir.toString()),
        Path.of(System.getProperty("java.home")))) {
      server.openFile(mainUri, mainCode);

      List<CompletionItem> items = server.completions(mainUri, positionAtMarker(mainCode, "/*caret*/"));
      assertTrue(containsLabel(items, "protectedMethod"),
          "Protected members should remain visible through subtype-qualified access");
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

  private static void compileJavaSource(Path sourceDir, Path outputDir, String fqn, String source) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("system java compiler not available");
    }
    Path sourceFile = sourceDir.resolve(fqn.replace('.', '/') + ".java");
    Files.createDirectories(sourceFile.getParent());
    Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
    int result = compiler.run(null, null, null,
        "-classpath", outputDir.toString(),
        "-d", outputDir.toString(),
        sourceFile.toString());
    assertEquals(0, result, "compilation should succeed");
  }
}
