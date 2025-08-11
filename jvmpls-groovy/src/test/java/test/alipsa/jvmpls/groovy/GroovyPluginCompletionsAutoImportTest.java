package test.alipsa.jvmpls.groovy;

import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.core.model.CompletionItem;
import se.alipsa.jvmpls.core.model.Position;
import se.alipsa.jvmpls.core.model.TextEdit;
import se.alipsa.jvmpls.core.server.CoreServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GroovyPluginCompletionsAutoImportTest {

  @Test
  void dotted_prefix_suggests_and_adds_import_when_missing() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-groovy-autoimport1");

    Path banana = dir.resolve("Banana.groovy");
    String bananaCode = "package thing\nclass Banana {}";
    Files.writeString(banana, bananaCode, StandardCharsets.UTF_8);
    String bananaUri = banana.toUri().toString();

    // No imports yet; typing thing.Ba<caret>
    Path main = dir.resolve("Main.groovy");
    String mainCode = """
      package demo
      class Main {
        static void run() {
          thing.Ba/*caret*/
        }
      }
      """;
    Files.writeString(main, mainCode, StandardCharsets.UTF_8);
    String mainUri = main.toUri().toString();

    try (CoreServer server = CoreServer.createDefault((u, d) -> {})) {
      server.openFile(bananaUri, bananaCode);
      server.openFile(mainUri,   mainCode);

      Position pos = positionAtMarker(mainCode, "/*caret*/");
      List<CompletionItem> items = server.completions(mainUri, pos);

      CompletionItem bananaItem = byLabel(items, "Banana");
      assertNotNull(bananaItem, "Expected 'Banana' completion for dotted prefix 'thing.Ba'");

      List<TextEdit> edits = bananaItem.getAdditionalTextEdits();
      assertNotNull(edits, "additionalTextEdits should not be null");
      assertFalse(edits.isEmpty(), "Expected an auto-import edit for 'import thing.Banana'");
      String combined = edits.stream().map(TextEdit::getNewText).reduce("", String::concat);
      assertTrue(combined.contains("import thing.Banana"),
          "Auto-import should insert: import thing.Banana");
      assertFalse(combined.contains(";"), "Groovy import should have no semicolon");
    }
  }

  @Test
  void no_import_edit_when_already_imported() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-groovy-autoimport2");

    Path banana = dir.resolve("Banana.groovy");
    String bananaCode = "package thing\nclass Banana {}";
    Files.writeString(banana, bananaCode, StandardCharsets.UTF_8);
    String bananaUri = banana.toUri().toString();

    Path main = dir.resolve("Main.groovy");
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
      server.openFile(bananaUri, bananaCode);
      server.openFile(mainUri,   mainCode);

      Position pos = positionAtMarker(mainCode, "/*caret*/");
      List<CompletionItem> items = server.completions(mainUri, pos);

      CompletionItem bananaItem = byLabel(items, "Banana");
      assertNotNull(bananaItem, "Expected 'Banana' from single-type import");
      assertTrue(bananaItem.getAdditionalTextEdits() == null
              || bananaItem.getAdditionalTextEdits().isEmpty(),
          "No auto-import edits expected when already imported.");
    }
  }

  // ---------- helpers ----------

  private static CompletionItem byLabel(List<CompletionItem> items, String label) {
    if (items == null) return null;
    for (CompletionItem it : items) {
      if (it != null && label.equals(it.getLabel())) return it;
    }
    return null;
  }

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
