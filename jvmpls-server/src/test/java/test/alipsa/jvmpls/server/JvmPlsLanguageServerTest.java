package test.alipsa.jvmpls.server;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.alipsa.jvmpls.server.JvmPlsLanguageServer;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that exercise the full LSP lifecycle over JSON-RPC
 * using piped streams to connect client and server in-process.
 */
class JvmPlsLanguageServerTest {

  private static final int TIMEOUT_SECONDS = 5;

  private JvmPlsLanguageServer server;
  private LanguageServer serverProxy;
  private TestLanguageClient testClient;
  private Future<Void> serverListening;
  private Future<Void> clientListening;
  private PipedOutputStream clientOut;
  private PipedOutputStream serverOut;
  private PipedInputStream clientIn;
  private PipedInputStream serverIn;

  @BeforeEach
  void setUp() throws Exception {
    // Two piped stream pairs:
    // client writes to clientOut -> server reads from serverIn
    // server writes to serverOut -> client reads from clientIn
    clientOut = new PipedOutputStream();
    serverIn = new PipedInputStream(clientOut);

    serverOut = new PipedOutputStream();
    clientIn = new PipedInputStream(serverOut);

    server = new JvmPlsLanguageServer();
    testClient = new TestLanguageClient();

    // Create server-side launcher: server reads from serverIn, writes to serverOut
    Launcher<LanguageClient> serverLauncher =
        LSPLauncher.createServerLauncher(server, serverIn, serverOut);
    server.connect(serverLauncher.getRemoteProxy());

    // Create client-side launcher: client reads from clientIn, writes to clientOut
    Launcher<LanguageServer> clientLauncher =
        LSPLauncher.createClientLauncher(testClient, clientIn, clientOut);
    serverProxy = clientLauncher.getRemoteProxy();

    // Start listening on daemon threads
    serverListening = serverLauncher.startListening();
    clientListening = clientLauncher.startListening();
  }

  @AfterEach
  void tearDown() throws Exception {
    // Shutdown the server gracefully
    if (serverProxy != null) {
      try {
        serverProxy.shutdown().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        // best effort
      }
    }

    // Close all streams to unblock listening threads
    closeQuietly(clientOut);
    closeQuietly(serverOut);
    closeQuietly(clientIn);
    closeQuietly(serverIn);

    // Cancel listening futures
    if (serverListening != null) serverListening.cancel(true);
    if (clientListening != null) clientListening.cancel(true);
  }

  // -------------------------------------------------------------------------
  // Test 1: initialize returns capabilities
  // -------------------------------------------------------------------------

  @Test
  void initialize_returnsCapabilities() throws Exception {
    InitializeResult result = initialize();

    assertNotNull(result.getCapabilities(), "capabilities should not be null");

    CompletionOptions completionProvider = result.getCapabilities().getCompletionProvider();
    assertNotNull(completionProvider, "completionProvider should not be null");
    assertTrue(completionProvider.getTriggerCharacters().contains("."),
        "trigger characters should include '.'");

    assertNotNull(result.getServerInfo(), "serverInfo should not be null");
    assertEquals("jvm-pls", result.getServerInfo().getName());
  }

  // -------------------------------------------------------------------------
  // Test 2: didOpen publishes diagnostics
  // -------------------------------------------------------------------------

  @Test
  void didOpen_publishesDiagnostics() throws Exception {
    initialize();

    Path dir = Files.createTempDirectory("jvm-pls-lsp-test");
    Path file = dir.resolve("Hello.java");
    String code = """
        public class Hello {
          void greet() { System.out.println("hi"); }
        }
        """;
    Files.writeString(file, code, StandardCharsets.UTF_8);
    String uri = file.toUri().toString();

    serverProxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(uri, "java", 1, code)));

    // Wait for diagnostics to arrive (async via piped streams)
    PublishDiagnosticsParams diagnostics = testClient.awaitDiagnostics(uri, TIMEOUT_SECONDS);
    assertNotNull(diagnostics, "should have received publishDiagnostics for " + uri);
    assertEquals(uri, diagnostics.getUri());
  }

  // -------------------------------------------------------------------------
  // Test 3: completion returns results
  // -------------------------------------------------------------------------

  @Test
  void completion_returnsResults() throws Exception {
    initialize();

    Path dir = Files.createTempDirectory("jvm-pls-lsp-completion");
    Path pkgDir = Files.createDirectories(dir.resolve("com/example"));

    // Foo.java defines class Foo in package com.example
    Path fooFile = pkgDir.resolve("Foo.java");
    String fooCode = "package com.example;\n\npublic class Foo {\n  public void doSomething() {}\n}\n";
    Files.writeString(fooFile, fooCode, StandardCharsets.UTF_8);
    String fooUri = fooFile.toUri().toString();

    // Bar.java references "Fo" - cursor at end of "Fo" in package com.example
    Path barFile = pkgDir.resolve("Bar.java");
    String barCode = "package com.example;\n\npublic class Bar {\n  void test() {\n    Fo\n  }\n}\n";
    Files.writeString(barFile, barCode, StandardCharsets.UTF_8);
    String barUri = barFile.toUri().toString();

    // Open both files to index them
    serverProxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(fooUri, "java", 1, fooCode)));
    serverProxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(barUri, "java", 1, barCode)));

    // Let indexing and async communication settle
    Thread.sleep(500);

    // Request completion at the end of "Fo" in barCode
    // Line 4: "    Fo" -> line index 4, character 6
    CompletionParams completionParams = new CompletionParams(
        new TextDocumentIdentifier(barUri),
        new Position(4, 6));

    Either<List<CompletionItem>, CompletionList> result =
        serverProxy.getTextDocumentService().completion(completionParams)
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertNotNull(result, "completion result should not be null");

    List<CompletionItem> items;
    if (result.isLeft()) {
      items = result.getLeft();
    } else {
      items = result.getRight().getItems();
    }

    assertNotNull(items, "completion items should not be null");
    boolean containsFoo = items.stream()
        .anyMatch(item -> item.getLabel().contains("Foo"));
    assertTrue(containsFoo,
        "completion should contain 'Foo', got: " + items.stream()
            .map(CompletionItem::getLabel).toList());
  }

  // -------------------------------------------------------------------------
  // Helper: send initialize + initialized
  // -------------------------------------------------------------------------

  private InitializeResult initialize() throws Exception {
    InitializeParams params = new InitializeParams();
    params.setCapabilities(new ClientCapabilities());
    params.setProcessId((int) ProcessHandle.current().pid());

    InitializeResult result = serverProxy.initialize(params)
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    serverProxy.initialized(new InitializedParams());

    return result;
  }

  private static void closeQuietly(java.io.Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (Exception ignored) {
        // best effort
      }
    }
  }

  // -------------------------------------------------------------------------
  // TestLanguageClient: collects diagnostics for assertions
  // -------------------------------------------------------------------------

  private static class TestLanguageClient implements LanguageClient {

    private final CopyOnWriteArrayList<PublishDiagnosticsParams> diagnosticsList =
        new CopyOnWriteArrayList<>();

    @Override
    public void telemetryEvent(Object object) {
      // no-op
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
      diagnosticsList.add(diagnostics);
    }

    @Override
    public void showMessage(MessageParams messageParams) {
      // no-op
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(
        ShowMessageRequestParams requestParams) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
      // no-op
    }

    /**
     * Wait for a publishDiagnostics call for the given URI, polling with timeout.
     */
    PublishDiagnosticsParams awaitDiagnostics(String uri, int timeoutSeconds) throws Exception {
      long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
      while (System.currentTimeMillis() < deadline) {
        for (PublishDiagnosticsParams params : diagnosticsList) {
          if (params.getUri().equals(uri)) {
            return params;
          }
        }
        Thread.sleep(50);
      }
      return null;
    }
  }
}
