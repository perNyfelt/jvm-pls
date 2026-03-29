package test.alipsa.jvmpls.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;

import se.alipsa.jvmpls.core.CoreFacade;
import se.alipsa.jvmpls.core.model.CompletionItem;
import se.alipsa.jvmpls.core.model.Diagnostic;
import se.alipsa.jvmpls.core.model.Location;
import se.alipsa.jvmpls.core.model.Range;
import se.alipsa.jvmpls.core.server.CoreServer;
import se.alipsa.jvmpls.server.JvmPlsLanguageServer;

class JvmPlsLanguageServerLifecycleTest {

  private static final String TEST_URI = "file:///Test.java";
  private static final String TEXT_DOCUMENT_SERVICE_LOGGER =
      "se.alipsa.jvmpls.server.JvmPlsTextDocumentService";

  @Test
  void exit_usesFailureCodeWhenShutdownWasNotRequested() {
    AtomicInteger observedExitCode = new AtomicInteger(-1);
    JvmPlsLanguageServer server =
        new JvmPlsLanguageServer(new NoOpCoreFacade(), () -> {}, observedExitCode::set);

    server.exit();

    assertEquals(1, server.getExitCode());
    assertEquals(1, observedExitCode.get());
  }

  @Test
  void shutdown_thenExit_usesSuccessCode() throws Exception {
    AtomicBoolean closed = new AtomicBoolean();
    AtomicInteger observedExitCode = new AtomicInteger(-1);
    JvmPlsLanguageServer server =
        new JvmPlsLanguageServer(
            new NoOpCoreFacade(), () -> closed.set(true), observedExitCode::set);

    Object shutdownResult = server.shutdown().get();
    server.exit();

    assertNull(shutdownResult);
    assertTrue(closed.get(), "core lifecycle should be closed during shutdown");
    assertEquals(0, server.getExitCode());
    assertEquals(0, observedExitCode.get());
  }

  @Test
  void shutdown_returnsNormallyWhenCoreCloseFails() throws Exception {
    AtomicInteger observedExitCode = new AtomicInteger(-1);
    JvmPlsLanguageServer server =
        new JvmPlsLanguageServer(
            new NoOpCoreFacade(),
            () -> {
              throw new Exception("boom");
            },
            observedExitCode::set);

    try (TestLogCapture logs = TestLogCapture.capture(JvmPlsLanguageServer.class)) {
      Object shutdownResult = server.shutdown().get();
      server.exit();

      assertNull(shutdownResult);
      assertEquals(0, server.getExitCode());
      assertEquals(0, observedExitCode.get());
      assertTrue(logs.contains(Level.SEVERE, "Failed to close core server during shutdown"));
    }
  }

  @Test
  void shutdown_ignoresNotificationsAndRejectsRequests() throws Exception {
    CountingCoreFacade core = new CountingCoreFacade();
    JvmPlsLanguageServer server = new JvmPlsLanguageServer(core, () -> {}, exitCode -> {});

    try (TestLogCapture logs = TestLogCapture.capture(TEXT_DOCUMENT_SERVICE_LOGGER)) {
      server.shutdown().get();

      server
          .getTextDocumentService()
          .didOpen(
              new DidOpenTextDocumentParams(
                  new TextDocumentItem(TEST_URI, "java", 1, "class Test {}")));
      server
          .getTextDocumentService()
          .didChange(
              new DidChangeTextDocumentParams(
                  new VersionedTextDocumentIdentifier(TEST_URI, 2),
                  List.of(new TextDocumentContentChangeEvent("class Test { int x; }"))));
      server
          .getTextDocumentService()
          .didClose(
              new org.eclipse.lsp4j.DidCloseTextDocumentParams(
                  new TextDocumentIdentifier(TEST_URI)));

      CompletionException completionFailure =
          assertThrows(
              CompletionException.class,
              () ->
                  server
                      .getTextDocumentService()
                      .completion(
                          new CompletionParams(
                              new TextDocumentIdentifier(TEST_URI), new Position(0, 0)))
                      .join());
      assertInvalidRequest(completionFailure);

      CompletionException definitionFailure =
          assertThrows(
              CompletionException.class,
              () ->
                  server
                      .getTextDocumentService()
                      .definition(
                          new DefinitionParams(
                              new TextDocumentIdentifier(TEST_URI), new Position(0, 0)))
                      .join());
      assertInvalidRequest(definitionFailure);

      assertEquals(0, core.openInvocations.get());
      assertEquals(0, core.changeInvocations.get());
      assertEquals(0, core.closeInvocations.get());
      assertEquals(0, core.completionInvocations.get());
      assertEquals(0, core.definitionInvocations.get());
      assertTrue(logs.contains(Level.WARNING, "Ignoring textDocument/didOpen after shutdown"));
      assertTrue(logs.contains(Level.WARNING, "Rejecting textDocument/completion after shutdown"));
    }
  }

  @Test
  void publicConstructor_publishesDiagnosticsThroughConnectedClient() {
    DiagnosticReturningCoreFacade core = new DiagnosticReturningCoreFacade();
    CapturingLanguageClient client = new CapturingLanguageClient();
    JvmPlsLanguageServer server = new JvmPlsLanguageServer(core, () -> {}, exitCode -> {});

    server.connect(client);
    server
        .getTextDocumentService()
        .didOpen(
            new DidOpenTextDocumentParams(
                new TextDocumentItem(TEST_URI, "java", 1, "class Test {}")));

    PublishDiagnosticsParams diagnostics = client.diagnostics.get();
    assertTrue(diagnostics != null, "diagnostics should be published through the connected client");
    assertEquals(TEST_URI, diagnostics.getUri());
    assertEquals(1, diagnostics.getDiagnostics().size());
    assertTrue(
        String.valueOf(diagnostics.getDiagnostics().getFirst().getMessage()).contains("boom"));
  }

  @Test
  void publicConstructor_rejectsCoreServerToAvoidDuplicateDiagnostics() {
    try (CoreServer coreServer = CoreServer.createDefault((uri, diagnostics) -> {})) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () -> new JvmPlsLanguageServer(coreServer, coreServer, exitCode -> {}));
      assertTrue(error.getMessage().contains("CoreServer"));
    }
  }

  private static void assertInvalidRequest(Throwable throwable) {
    Throwable cause = throwable.getCause();
    assertTrue(
        cause instanceof ResponseErrorException, "failure should be a ResponseErrorException");
    ResponseErrorException error = (ResponseErrorException) cause;
    assertEquals(ResponseErrorCode.InvalidRequest.getValue(), error.getResponseError().getCode());
  }

  private static class NoOpCoreFacade implements CoreFacade {

    @Override
    public List<Diagnostic> openFile(String uri, String text) {
      return List.of();
    }

    @Override
    public List<Diagnostic> changeFile(String uri, String text) {
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
      return List.of();
    }

    @Override
    public Optional<Location> definition(
        String uri, se.alipsa.jvmpls.core.model.Position position) {
      return Optional.empty();
    }
  }

  private static final class CountingCoreFacade extends NoOpCoreFacade {

    private final AtomicInteger openInvocations = new AtomicInteger();
    private final AtomicInteger changeInvocations = new AtomicInteger();
    private final AtomicInteger closeInvocations = new AtomicInteger();
    private final AtomicInteger completionInvocations = new AtomicInteger();
    private final AtomicInteger definitionInvocations = new AtomicInteger();

    @Override
    public List<Diagnostic> openFile(String uri, String text) {
      openInvocations.incrementAndGet();
      return List.of();
    }

    @Override
    public List<Diagnostic> changeFile(String uri, String text) {
      changeInvocations.incrementAndGet();
      return List.of();
    }

    @Override
    public void closeFile(String uri) {
      closeInvocations.incrementAndGet();
    }

    @Override
    public List<CompletionItem> completions(
        String uri, se.alipsa.jvmpls.core.model.Position position) {
      completionInvocations.incrementAndGet();
      return List.of();
    }

    @Override
    public Optional<Location> definition(
        String uri, se.alipsa.jvmpls.core.model.Position position) {
      definitionInvocations.incrementAndGet();
      return Optional.empty();
    }
  }

  private static final class DiagnosticReturningCoreFacade extends NoOpCoreFacade {

    @Override
    public List<Diagnostic> openFile(String uri, String text) {
      return List.of(
          new Diagnostic(
              new Range(
                  new se.alipsa.jvmpls.core.model.Position(0, 0),
                  new se.alipsa.jvmpls.core.model.Position(0, 1)),
              "boom",
              Diagnostic.Severity.ERROR,
              "test",
              "E001"));
    }
  }

  private static final class CapturingLanguageClient implements LanguageClient {

    private final AtomicReference<PublishDiagnosticsParams> diagnostics = new AtomicReference<>();

    @Override
    public void telemetryEvent(Object object) {}

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
      this.diagnostics.set(diagnostics);
    }

    @Override
    public void showMessage(MessageParams messageParams) {}

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(
        ShowMessageRequestParams requestParams) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {}
  }
}
