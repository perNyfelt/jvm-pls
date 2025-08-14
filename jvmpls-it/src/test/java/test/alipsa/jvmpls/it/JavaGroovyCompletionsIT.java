package test.alipsa.jvmpls.it;

import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.core.server.CoreServer;
import se.alipsa.jvmpls.core.model.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaGroovyCompletionsIT {

  private static final String CARET = "/*caret*/";

  @Test
  void groovy_dotted_prefix_sees_java_and_adds_import_when_missing() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-it-g2j");

    // Java type
    Path apple = dir.resolve("Apple.java");
    String appleCode = "package demo; public class Apple {}";
    Files.writeString(apple, appleCode, StandardCharsets.UTF_8);
    String appleUri = apple.toUri().toString();

    // Groovy main (no imports) typing a dotted prefix "demo.Ap"
    Path gMain = dir.resolve("GMain.groovy");
    String gMainWithMarker = """
      package demo2
      class GMain {
        static void run() {
          demo.Ap/*caret*/
        }
      }
      """;
    String gMainCode = gMainWithMarker.replace(CARET, "");
    Files.writeString(gMain, gMainCode, StandardCharsets.UTF_8);
    String gMainUri = gMain.toUri().toString();

    Position pos = positionAtMarker(gMainWithMarker, CARET);

    try (CoreServer server = CoreServer.createDefault((uri, diags) -> {})) {
      // Open both files so both plugins can index symbols
      server.openFile(appleUri, appleCode);
      server.openFile(gMainUri, gMainCode);

      List<CompletionItem> items = server.completions(gMainUri, pos);
      CompletionItem appleItem = byLabel(items, "Apple");
      assertNotNull(appleItem, "Groovy dotted prefix 'demo.Ap' should propose Java class Apple");

      // Because GMain.groovy has no import for demo.Apple and it's not in the same package/defaults,
      // the suggestion should carry an auto-import edit.
      List<TextEdit> edits = appleItem.getAdditionalTextEdits();
      assertNotNull(edits, "Expected additional text edits for auto-import");
      assertFalse(edits.isEmpty(), "Expected a non-empty auto-import edit list");
      String editText = edits.get(0).getNewText();
      assertTrue(editText.contains("import demo.Apple"),
          "Auto-import edit should insert 'import demo.Apple' in Groovy");
    }
  }

  @Test
  void java_undotted_prefix_from_star_import_sees_groovy_without_edit() throws Exception {
    Path dir = Files.createTempDirectory("jvmpls-it-j2g");

    // Groovy type
    Path banana = dir.resolve("Banana.groovy");
    String bananaCode = "package thing\nclass Banana {}";
    Files.writeString(banana, bananaCode, StandardCharsets.UTF_8);
    String bananaUri = banana.toUri().toString();

    // Java main with star import, typing "Ba"
    Path jMain = dir.resolve("JMain.java");
    String jMainWithMarker = """
      package demo2;
      import thing.*;
      class JMain {
        void run() {
          Ba/*caret*/
        }
      }
      """;
    String jMainCode = jMainWithMarker.replace(CARET, "");
    Files.writeString(jMain, jMainCode, StandardCharsets.UTF_8);
    String jMainUri = jMain.toUri().toString();

    Position pos = positionAtMarker(jMainWithMarker, CARET);

    try (CoreServer server = CoreServer.createDefault((uri, diags) -> {})) {
      server.openFile(bananaUri, bananaCode);
      server.openFile(jMainUri,    jMainCode);

      List<CompletionItem> items = server.completions(jMainUri, pos);
      CompletionItem bananaItem = byLabel(items, "Banana");
      assertNotNull(bananaItem, "Java with 'import thing.*;' and prefix 'Ba' should propose Groovy class Banana");

      // Star import makes Banana visible; Java completion should NOT propose an extra import edit.
      List<TextEdit> edits = bananaItem.getAdditionalTextEdits();
      assertTrue(edits == null || edits.isEmpty(),
          "No auto-import edits expected when 'import thing.*;' already makes Banana visible");
    }
  }

  // ---- helpers -------------------------------------------------------------------------------

  private static Position positionAtMarker(String text, String marker) {
    int idx = text.indexOf(marker);
    assertTrue(idx >= 0, "Marker not found in text");
    int line = 0, col = 0;
    for (int i = 0; i < idx; i++) {
      char c = text.charAt(i);
      if (c == '\n') { line++; col = 0; }
      else { col++; }
    }
    return new Position(line, col);
  }

  private static CompletionItem byLabel(List<CompletionItem> items, String label) {
    for (var it : items) {
      if (label.equals(it.getLabel())) return it;
    }
    return null;
  }
}
