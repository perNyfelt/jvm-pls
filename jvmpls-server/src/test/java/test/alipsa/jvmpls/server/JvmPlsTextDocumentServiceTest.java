package test.alipsa.jvmpls.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DeclarationParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.CoreFacade;
import se.alipsa.jvmpls.core.model.CodeActionInfo;
import se.alipsa.jvmpls.core.model.CompletionItem;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.model.HoverInfo;
import se.alipsa.jvmpls.core.model.Range;
import se.alipsa.jvmpls.core.model.SignatureHelpInfo;
import se.alipsa.jvmpls.core.model.SymbolInfo;
import se.alipsa.jvmpls.core.model.TextEdit;
import se.alipsa.jvmpls.server.DocumentFormatter;
import se.alipsa.jvmpls.server.JvmPlsTextDocumentService;

class JvmPlsTextDocumentServiceTest {

  private static final String TEST_URI = "file:///Test.java";

  @Test
  void didChange_ignoresEmptyContentChanges() {
    FakeCoreFacade core = new FakeCoreFacade();
    JvmPlsTextDocumentService service = new JvmPlsTextDocumentService(core);

    service.didChange(
        new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(TEST_URI, 1), Collections.emptyList()));

    assertEquals(0, core.changeInvocations.get());
  }

  @Test
  void didChange_throwsIllegalArgumentExceptionForUnknownDocument() {
    FakeCoreFacade core = new FakeCoreFacade();
    JvmPlsTextDocumentService service = new JvmPlsTextDocumentService(core);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.didChange(
                new DidChangeTextDocumentParams(
                    new VersionedTextDocumentIdentifier(TEST_URI, 1),
                    List.of(new TextDocumentContentChangeEvent("class Test {}")))));
  }

  @Test
  void didSave_reanalyzesOpenDocument() {
    FakeCoreFacade core = new FakeCoreFacade();
    JvmPlsTextDocumentService service = new JvmPlsTextDocumentService(core);

    service.didOpen(
        new DidOpenTextDocumentParams(new TextDocumentItem(TEST_URI, "java", 1, "class Test {}")));
    service.didSave(new DidSaveTextDocumentParams(new TextDocumentIdentifier(TEST_URI)));

    assertEquals(1, core.analyzeInvocations.get());
  }

  @Test
  void completion_returnsEmptyListWhenCoreThrows() throws Exception {
    FakeCoreFacade core = new FakeCoreFacade();
    core.completionFailure = new IllegalStateException("boom");
    JvmPlsTextDocumentService service = new JvmPlsTextDocumentService(core);

    try (TestLogCapture logs = TestLogCapture.capture(JvmPlsTextDocumentService.class)) {
      Either<List<org.eclipse.lsp4j.CompletionItem>, CompletionList> result =
          service
              .completion(
                  new CompletionParams(new TextDocumentIdentifier(TEST_URI), new Position(0, 0)))
              .get(5, TimeUnit.SECONDS);

      assertTrue(result.isLeft(), "completion fallback should return a left list");
      assertTrue(result.getLeft().isEmpty(), "completion fallback should be empty");
      assertTrue(logs.contains(Level.SEVERE, "Completion request failed"));
    }
  }

  @Test
  void declaration_mapsOptionalLocationToSingletonList() throws Exception {
    FakeCoreFacade core = new FakeCoreFacade();
    core.definitionResult =
        Optional.of(
            new se.alipsa.jvmpls.core.model.Location(
                TEST_URI,
                new Range(
                    new se.alipsa.jvmpls.core.model.Position(2, 1),
                    new se.alipsa.jvmpls.core.model.Position(2, 4))));
    JvmPlsTextDocumentService service = new JvmPlsTextDocumentService(core);

    Either<List<? extends Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
        service
            .declaration(
                new DeclarationParams(new TextDocumentIdentifier(TEST_URI), new Position(0, 0)))
            .get(5, TimeUnit.SECONDS);

    assertTrue(result.isLeft());
    assertEquals(1, result.getLeft().size());
    assertEquals(TEST_URI, result.getLeft().getFirst().getUri());
  }

  @Test
  void definition_mapsOptionalLocationToSingletonList() throws Exception {
    FakeCoreFacade core = new FakeCoreFacade();
    core.definitionResult =
        Optional.of(
            new se.alipsa.jvmpls.core.model.Location(
                TEST_URI,
                new Range(
                    new se.alipsa.jvmpls.core.model.Position(1, 2),
                    new se.alipsa.jvmpls.core.model.Position(1, 7))));
    JvmPlsTextDocumentService service = new JvmPlsTextDocumentService(core);

    Either<List<? extends Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
        service
            .definition(
                new DefinitionParams(new TextDocumentIdentifier(TEST_URI), new Position(0, 0)))
            .get(5, TimeUnit.SECONDS);

    assertTrue(result.isLeft(), "definition should return locations on the left side");
    assertEquals(1, result.getLeft().size());
    assertEquals(TEST_URI, result.getLeft().getFirst().getUri());
  }

  @Test
  void definition_returnsEmptyListWhenConversionFails() throws Exception {
    FakeCoreFacade core = new FakeCoreFacade();
    core.definitionResult =
        Optional.of(
            new se.alipsa.jvmpls.core.model.Location(
                TEST_URI, new Range(null, new se.alipsa.jvmpls.core.model.Position(1, 7))));
    JvmPlsTextDocumentService service = new JvmPlsTextDocumentService(core);

    try (TestLogCapture logs = TestLogCapture.capture(JvmPlsTextDocumentService.class)) {
      Either<List<? extends Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
          service
              .definition(
                  new DefinitionParams(new TextDocumentIdentifier(TEST_URI), new Position(0, 0)))
              .get(5, TimeUnit.SECONDS);

      assertTrue(result.isLeft(), "definition fallback should return locations on the left side");
      assertNotNull(result.getLeft());
      assertFalse(result.getLeft().iterator().hasNext(), "definition fallback should be empty");
      assertTrue(logs.contains(Level.SEVERE, "Definition request failed"));
    }
  }

  @Test
  void documentHighlight_filtersReferencesToCurrentFile() throws Exception {
    FakeCoreFacade core = new FakeCoreFacade();
    core.referencesResult =
        List.of(
            new se.alipsa.jvmpls.core.model.Location(
                TEST_URI,
                new Range(
                    new se.alipsa.jvmpls.core.model.Position(1, 0),
                    new se.alipsa.jvmpls.core.model.Position(1, 4))),
            new se.alipsa.jvmpls.core.model.Location(
                "file:///Other.java",
                new Range(
                    new se.alipsa.jvmpls.core.model.Position(0, 0),
                    new se.alipsa.jvmpls.core.model.Position(0, 4))));
    JvmPlsTextDocumentService service = new JvmPlsTextDocumentService(core);

    List<? extends DocumentHighlight> highlights =
        service
            .documentHighlight(
                new DocumentHighlightParams(
                    new TextDocumentIdentifier(TEST_URI), new Position(0, 0)))
            .get(5, TimeUnit.SECONDS);

    assertEquals(1, highlights.size());
    assertEquals(1, highlights.getFirst().getRange().getStart().getLine());
  }

  @Test
  void formatting_usesInjectedFormatter() throws Exception {
    FakeCoreFacade core = new FakeCoreFacade();
    DocumentFormatter formatter =
        (uri, text) ->
            List.of(
                new TextEdit(
                    new Range(
                        new se.alipsa.jvmpls.core.model.Position(0, 0),
                        new se.alipsa.jvmpls.core.model.Position(0, text.length())),
                    text.toUpperCase()));
    JvmPlsTextDocumentService service = new JvmPlsTextDocumentService(core, formatter);

    service.didOpen(
        new DidOpenTextDocumentParams(new TextDocumentItem(TEST_URI, "java", 1, "class Test {}")));

    List<? extends org.eclipse.lsp4j.TextEdit> edits =
        service
            .formatting(
                new DocumentFormattingParams(
                    new TextDocumentIdentifier(TEST_URI), new FormattingOptions(2, true)))
            .get(5, TimeUnit.SECONDS);

    assertEquals(1, edits.size());
    assertEquals("CLASS TEST {}", edits.getFirst().getNewText());
  }

  private static final class FakeCoreFacade implements CoreFacade {

    private final AtomicInteger analyzeInvocations = new AtomicInteger();
    private final AtomicInteger changeInvocations = new AtomicInteger();
    private RuntimeException completionFailure;
    private Optional<se.alipsa.jvmpls.core.model.Location> definitionResult = Optional.empty();
    private List<se.alipsa.jvmpls.core.model.Location> referencesResult = List.of();

    @Override
    public List<Diagnostic> openFile(String uri, String text) {
      return List.of();
    }

    @Override
    public List<Diagnostic> changeFile(String uri, String text) {
      changeInvocations.incrementAndGet();
      return List.of();
    }

    @Override
    public void closeFile(String uri) {}

    @Override
    public List<Diagnostic> analyze(String uri) {
      analyzeInvocations.incrementAndGet();
      return List.of();
    }

    @Override
    public List<CompletionItem> completions(
        String uri, se.alipsa.jvmpls.core.model.Position position) {
      if (completionFailure != null) {
        throw completionFailure;
      }
      return List.of();
    }

    @Override
    public Optional<se.alipsa.jvmpls.core.model.Location> definition(
        String uri, se.alipsa.jvmpls.core.model.Position position) {
      return definitionResult;
    }

    @Override
    public Optional<se.alipsa.jvmpls.core.model.Location> typeDefinition(
        String uri, se.alipsa.jvmpls.core.model.Position position) {
      return Optional.empty();
    }

    @Override
    public Optional<HoverInfo> hover(String uri, se.alipsa.jvmpls.core.model.Position position) {
      return Optional.empty();
    }

    @Override
    public List<se.alipsa.jvmpls.core.model.Location> references(
        String uri, se.alipsa.jvmpls.core.model.Position position, boolean includeDeclaration) {
      return referencesResult;
    }

    @Override
    public List<SymbolInfo> documentSymbols(String uri) {
      return List.of();
    }

    @Override
    public List<SymbolInfo> workspaceSymbols(String query) {
      return List.of();
    }

    @Override
    public List<se.alipsa.jvmpls.core.model.Location> implementations(
        String uri, se.alipsa.jvmpls.core.model.Position position) {
      return List.of();
    }

    @Override
    public Optional<SignatureHelpInfo> signatureHelp(
        String uri, se.alipsa.jvmpls.core.model.Position position) {
      return Optional.empty();
    }

    @Override
    public List<CodeActionInfo> codeActions(String uri, Range range, List<Diagnostic> diagnostics) {
      return List.of();
    }

    @Override
    public Optional<se.alipsa.jvmpls.core.model.PrepareRenameInfo> prepareRename(
        String uri, se.alipsa.jvmpls.core.model.Position position) {
      return Optional.empty();
    }

    @Override
    public Optional<se.alipsa.jvmpls.core.model.RenamePlan> rename(
        String uri, se.alipsa.jvmpls.core.model.Position position, String newName) {
      return Optional.empty();
    }

    @Override
    public List<se.alipsa.jvmpls.core.model.CallHierarchyItemInfo> prepareCallHierarchy(
        String uri, se.alipsa.jvmpls.core.model.Position position) {
      return List.of();
    }

    @Override
    public List<se.alipsa.jvmpls.core.model.IncomingCallInfo> incomingCalls(String symbolFqn) {
      return List.of();
    }

    @Override
    public List<se.alipsa.jvmpls.core.model.OutgoingCallInfo> outgoingCalls(String symbolFqn) {
      return List.of();
    }
  }
}
