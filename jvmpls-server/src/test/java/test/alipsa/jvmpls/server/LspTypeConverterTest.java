package test.alipsa.jvmpls.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.server.LspTypeConverter;

class LspTypeConverterTest {

  // -------------------------------------------------------------------------
  // Position
  // -------------------------------------------------------------------------

  @Test
  void toLspPosition() {
    var core = new se.alipsa.jvmpls.core.model.Position(3, 7);
    org.eclipse.lsp4j.Position lsp = LspTypeConverter.toLsp(core);
    assertEquals(3, lsp.getLine());
    assertEquals(7, lsp.getCharacter());
  }

  @Test
  void toCorePosition() {
    var lsp = new org.eclipse.lsp4j.Position(5, 12);
    se.alipsa.jvmpls.core.model.Position core = LspTypeConverter.toCore(lsp);
    assertEquals(5, core.line);
    assertEquals(12, core.column);
  }

  // -------------------------------------------------------------------------
  // Range
  // -------------------------------------------------------------------------

  @Test
  void toLspRange() {
    var start = new se.alipsa.jvmpls.core.model.Position(1, 0);
    var end = new se.alipsa.jvmpls.core.model.Position(1, 10);
    var core = new se.alipsa.jvmpls.core.model.Range(start, end);
    org.eclipse.lsp4j.Range lsp = LspTypeConverter.toLsp(core);
    assertEquals(1, lsp.getStart().getLine());
    assertEquals(0, lsp.getStart().getCharacter());
    assertEquals(1, lsp.getEnd().getLine());
    assertEquals(10, lsp.getEnd().getCharacter());
  }

  // -------------------------------------------------------------------------
  // Location
  // -------------------------------------------------------------------------

  @Test
  void toLspLocation() {
    var range =
        new se.alipsa.jvmpls.core.model.Range(
            new se.alipsa.jvmpls.core.model.Position(0, 0),
            new se.alipsa.jvmpls.core.model.Position(0, 5));
    var core = new se.alipsa.jvmpls.core.model.Location("file:///foo/Bar.java", range);
    org.eclipse.lsp4j.Location lsp = LspTypeConverter.toLsp(core);
    assertEquals("file:///foo/Bar.java", lsp.getUri());
    assertEquals(0, lsp.getRange().getStart().getLine());
    assertEquals(5, lsp.getRange().getEnd().getCharacter());
  }

  // -------------------------------------------------------------------------
  // DiagnosticSeverity
  // -------------------------------------------------------------------------

  @Test
  void toLspSeverityError() {
    assertEquals(
        org.eclipse.lsp4j.DiagnosticSeverity.Error,
        LspTypeConverter.toLsp(Diagnostic.Severity.ERROR));
  }

  @Test
  void toLspSeverityWarning() {
    assertEquals(
        org.eclipse.lsp4j.DiagnosticSeverity.Warning,
        LspTypeConverter.toLsp(Diagnostic.Severity.WARNING));
  }

  @Test
  void toLspSeverityInformation() {
    assertEquals(
        org.eclipse.lsp4j.DiagnosticSeverity.Information,
        LspTypeConverter.toLsp(Diagnostic.Severity.INFORMATION));
  }

  @Test
  void toLspSeverityHint() {
    assertEquals(
        org.eclipse.lsp4j.DiagnosticSeverity.Hint,
        LspTypeConverter.toLsp(Diagnostic.Severity.HINT));
  }

  // -------------------------------------------------------------------------
  // Diagnostic
  // -------------------------------------------------------------------------

  @Test
  void toLspDiagnostic() {
    var range =
        new se.alipsa.jvmpls.core.model.Range(
            new se.alipsa.jvmpls.core.model.Position(2, 4),
            new se.alipsa.jvmpls.core.model.Position(2, 9));
    var core =
        new Diagnostic(
            range, "something went wrong", Diagnostic.Severity.WARNING, "TestSource", "W001");
    org.eclipse.lsp4j.Diagnostic lsp = LspTypeConverter.toLsp(core);
    assertEquals(2, lsp.getRange().getStart().getLine());
    assertEquals(4, lsp.getRange().getStart().getCharacter());
    assertEquals("something went wrong", lsp.getMessage().getLeft());
    assertEquals(org.eclipse.lsp4j.DiagnosticSeverity.Warning, lsp.getSeverity());
    assertEquals("TestSource", lsp.getSource());
    assertEquals("W001", lsp.getCode().getLeft());
  }

  @Test
  void toLspDiagnosticNullCode() {
    var range =
        new se.alipsa.jvmpls.core.model.Range(
            new se.alipsa.jvmpls.core.model.Position(0, 0),
            new se.alipsa.jvmpls.core.model.Position(0, 1));
    var core = new Diagnostic(range, "oops", Diagnostic.Severity.ERROR, "src", null);
    org.eclipse.lsp4j.Diagnostic lsp = LspTypeConverter.toLsp(core);
    assertNull(lsp.getCode());
  }

  // -------------------------------------------------------------------------
  // TextEdit
  // -------------------------------------------------------------------------

  @Test
  void toLspTextEdit() {
    var range =
        new se.alipsa.jvmpls.core.model.Range(
            new se.alipsa.jvmpls.core.model.Position(0, 0),
            new se.alipsa.jvmpls.core.model.Position(0, 3));
    var core = new se.alipsa.jvmpls.core.model.TextEdit(range, "newText");
    org.eclipse.lsp4j.TextEdit lsp = LspTypeConverter.toLsp(core);
    assertEquals(0, lsp.getRange().getStart().getLine());
    assertEquals(3, lsp.getRange().getEnd().getCharacter());
    assertEquals("newText", lsp.getNewText());
  }

  // -------------------------------------------------------------------------
  // CompletionItem
  // -------------------------------------------------------------------------

  @Test
  void toLspCompletionItemBasic() {
    var range =
        new se.alipsa.jvmpls.core.model.Range(
            new se.alipsa.jvmpls.core.model.Position(0, 0),
            new se.alipsa.jvmpls.core.model.Position(0, 0));
    var loc = new se.alipsa.jvmpls.core.model.Location("file:///A.java", range);
    var core =
        new se.alipsa.jvmpls.core.model.CompletionItem(
            "myMethod", "detail text", "myMethod()", loc);
    org.eclipse.lsp4j.CompletionItem lsp = LspTypeConverter.toLsp(core);
    assertEquals("myMethod", lsp.getLabel());
    assertEquals("detail text", lsp.getDetail());
    assertEquals("myMethod()", lsp.getInsertText());
    assertTrue(lsp.getAdditionalTextEdits() == null || lsp.getAdditionalTextEdits().isEmpty());
  }

  @Test
  void toLspCompletionItemWithTextEdits() {
    var range =
        new se.alipsa.jvmpls.core.model.Range(
            new se.alipsa.jvmpls.core.model.Position(0, 0),
            new se.alipsa.jvmpls.core.model.Position(0, 0));
    var loc = new se.alipsa.jvmpls.core.model.Location("file:///A.java", range);
    var editRange =
        new se.alipsa.jvmpls.core.model.Range(
            new se.alipsa.jvmpls.core.model.Position(1, 0),
            new se.alipsa.jvmpls.core.model.Position(1, 0));
    var edit = new se.alipsa.jvmpls.core.model.TextEdit(editRange, "import Foo;\n");
    var core =
        new se.alipsa.jvmpls.core.model.CompletionItem(
            "Foo", "com.example.Foo", "Foo", loc, List.of(edit));
    org.eclipse.lsp4j.CompletionItem lsp = LspTypeConverter.toLsp(core);
    assertEquals(1, lsp.getAdditionalTextEdits().size());
    assertEquals("import Foo;\n", lsp.getAdditionalTextEdits().get(0).getNewText());
  }

  @Test
  void toLspCompletionItemIncludesTypeDetailInDetail() {
    var range =
        new se.alipsa.jvmpls.core.model.Range(
            new se.alipsa.jvmpls.core.model.Position(0, 0),
            new se.alipsa.jvmpls.core.model.Position(0, 0));
    var loc = new se.alipsa.jvmpls.core.model.Location("file:///A.java", range);
    var core =
        new se.alipsa.jvmpls.core.model.CompletionItem(
            "add", "java.util.List(java.lang.Object)boolean", "add", loc, List.of(), "boolean");

    org.eclipse.lsp4j.CompletionItem lsp = LspTypeConverter.toLsp(core);

    assertEquals("java.util.List(java.lang.Object)boolean : boolean", lsp.getDetail());
  }

  // -------------------------------------------------------------------------
  // Batch conversions
  // -------------------------------------------------------------------------

  @Test
  void toLspDiagnosticsList() {
    var range =
        new se.alipsa.jvmpls.core.model.Range(
            new se.alipsa.jvmpls.core.model.Position(0, 0),
            new se.alipsa.jvmpls.core.model.Position(0, 1));
    var d1 = new Diagnostic(range, "err1", Diagnostic.Severity.ERROR, "s", "E1");
    var d2 = new Diagnostic(range, "err2", Diagnostic.Severity.HINT, "s", "H1");
    List<org.eclipse.lsp4j.Diagnostic> lspList = LspTypeConverter.toLspDiagnostics(List.of(d1, d2));
    assertEquals(2, lspList.size());
    assertEquals("err1", lspList.get(0).getMessage().getLeft());
    assertEquals("err2", lspList.get(1).getMessage().getLeft());
    assertEquals(org.eclipse.lsp4j.DiagnosticSeverity.Hint, lspList.get(1).getSeverity());
  }

  @Test
  void toLspCompletionItemsList() {
    var range =
        new se.alipsa.jvmpls.core.model.Range(
            new se.alipsa.jvmpls.core.model.Position(0, 0),
            new se.alipsa.jvmpls.core.model.Position(0, 0));
    var loc = new se.alipsa.jvmpls.core.model.Location("file:///A.java", range);
    var c1 = new se.alipsa.jvmpls.core.model.CompletionItem("a", "da", "a", loc);
    var c2 = new se.alipsa.jvmpls.core.model.CompletionItem("b", "db", "b", loc);
    List<org.eclipse.lsp4j.CompletionItem> lspList =
        LspTypeConverter.toLspCompletionItems(List.of(c1, c2));
    assertEquals(2, lspList.size());
    assertEquals("a", lspList.get(0).getLabel());
    assertEquals("b", lspList.get(1).getLabel());
  }
}
