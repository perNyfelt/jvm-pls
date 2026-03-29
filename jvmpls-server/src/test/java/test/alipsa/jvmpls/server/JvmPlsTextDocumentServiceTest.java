package test.alipsa.jvmpls.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.CoreFacade;
import se.alipsa.jvmpls.core.model.CompletionItem;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.model.Range;
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

  private static final class FakeCoreFacade implements CoreFacade {

    private final AtomicInteger changeInvocations = new AtomicInteger();
    private RuntimeException completionFailure;
    private Optional<se.alipsa.jvmpls.core.model.Location> definitionResult = Optional.empty();

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
  }
}
